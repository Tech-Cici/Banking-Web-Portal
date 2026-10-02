/*
 * WHAT A MOVEMENT WAS, AND WHO WAS ON THE OTHER END.
 *
 * The ledger could say that 5,000 left an account and not who it went to. A statement
 * line read "Transfer to **** 7898 - awaiting approval", which names an account and not
 * a person, and the dashboard could only say "money moved". The one question a customer
 * asks of a statement - who was this? - was the one it could not answer.
 *
 * SNAPSHOTTED, NOT JOINED. The name is copied in when the entry is posted rather than
 * looked up when the statement is read. Two reasons, the second being the important one:
 *
 *   1. Most entries have no counterparty. A deposit, a withdrawal and an opening balance
 *      are the customer and the bank, so a join would be an outer join across two tables
 *      to add nothing to most rows.
 *
 *   2. A STATEMENT MUST SAY WHAT IT SAID. Joined at read time, a customer who changed
 *      their name would retroactively rewrite every statement they had ever appeared on,
 *      and a payment made to "J. Mukamana" in March would silently become a payment to
 *      somebody who did not exist under that name until July. A statement records a
 *      moment; the name belongs to the moment too.
 */

-- ------------------------------------------------------------------ the parties

/*
 * NULLABLE, and null is the ordinary case: it means this movement had no other party.
 * There is deliberately no placeholder - "N/A" or an empty string in a name column is a
 * value that reads as a fact, and every screen would then have to know to hide it.
 */
ALTER TABLE account_transactions ADD COLUMN counterparty_name VARCHAR(160);

/*
 * The MASK, never the number. Two people can share a name, and paying the wrong one of
 * them is the mistake this whole flow exists to prevent, so the last four digits sit
 * beside the name. The full number is not here and must never be: a statement is the
 * most-forwarded, most-screenshotted document a bank produces.
 */
ALTER TABLE account_transactions ADD COLUMN counterparty_mask VARCHAR(24);

-- ------------------------------------------------------------------ what happened

/*
 * WHY A KIND AND NOT JUST direction + counterparty.
 *
 * The first version of this migration had only the two columns above, and the client was
 * to word the line from the direction: a CREDIT with a counterparty is "you received X
 * from Y", a DEBIT is "you sent X to Y". That is wrong for one case, and it is the case
 * where being wrong matters most.
 *
 * A REFUSED TRANSFER PUTS THE MONEY BACK as a CREDIT, and the other party on it is the
 * payee who never got paid. Worded from the direction it reads "you received 4,000 from
 * Ciara" - describing a payment that did not happen, to a customer whose payment had
 * just failed. So the server names the movement and the client only words it.
 *
 * The alternative was for the client to look for "refused" in the description. Deciding
 * what a screen says by matching on display text is how a copy edit becomes a bug in the
 * meaning of a statement line.
 */
ALTER TABLE account_transactions
    ADD COLUMN movement_kind VARCHAR(24) NOT NULL DEFAULT 'CASH';

ALTER TABLE account_transactions
    ADD CONSTRAINT account_transactions_movement_kind_check
        CHECK (movement_kind IN ('CASH', 'TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_RETURNED'));

-- ------------------------------------------------------------------ existing rows

/*
 * BACKFILLED FROM THE IDEMPOTENCY KEY, which is the one thing already on these rows that
 * says what they were. The keys are built by TransferService and are deterministic:
 * "transfer-out-<client key>", "transfer-in-<transfer id>", "transfer-reversal-<transfer
 * id>". Everything else predates transfers and is genuinely cash.
 *
 * Without this, transfers already in the database would be labelled CASH and show no
 * other party - a wrong statement line rather than a missing one, which is worse.
 */
UPDATE account_transactions SET movement_kind = 'TRANSFER_OUT'
    WHERE idempotency_key LIKE 'transfer-out-%';

UPDATE account_transactions SET movement_kind = 'TRANSFER_IN'
    WHERE idempotency_key LIKE 'transfer-in-%';

UPDATE account_transactions SET movement_kind = 'TRANSFER_RETURNED'
    WHERE idempotency_key LIKE 'transfer-reversal-%';

/*
 * And the names, recovered through the transfer that produced each entry.
 *
 * `transfers` records which entry it posted for each outcome - debit_entry_id,
 * credit_entry_id, reversal_entry_id - so each row can be matched back to the account on
 * the other side without guessing. The name is read as it is TODAY, which is the best
 * available answer for a row posted before the column existed; every entry posted from
 * now on records the name at the time instead.
 *
 * Correlated subqueries rather than UPDATE ... FROM, because this has to run on H2 as
 * well as PostgreSQL - the tests use H2 and a migration that only works on one of them
 * is a migration that fails on a Friday.
 */
UPDATE account_transactions t
SET counterparty_mask = (
        SELECT ca.masked_number
        FROM transfers tr
                 JOIN customer_accounts ca ON ca.id = tr.destination_account_id
        WHERE tr.debit_entry_id = t.id),
    counterparty_name = (
        SELECT COALESCE(
                       (SELECT co.name FROM companies co WHERE co.id = ca.company_id),
                       (SELECT cu.full_name FROM customers cu WHERE cu.id = ca.customer_id))
        FROM transfers tr
                 JOIN customer_accounts ca ON ca.id = tr.destination_account_id
        WHERE tr.debit_entry_id = t.id)
WHERE t.movement_kind = 'TRANSFER_OUT'
  AND EXISTS (SELECT 1 FROM transfers tr WHERE tr.debit_entry_id = t.id);

/* An arrival names the SENDER. */
UPDATE account_transactions t
SET counterparty_mask = (
        SELECT ca.masked_number
        FROM transfers tr
                 JOIN customer_accounts ca ON ca.id = tr.source_account_id
        WHERE tr.credit_entry_id = t.id),
    counterparty_name = (
        SELECT COALESCE(
                       (SELECT co.name FROM companies co WHERE co.id = ca.company_id),
                       (SELECT cu.full_name FROM customers cu WHERE cu.id = ca.customer_id))
        FROM transfers tr
                 JOIN customer_accounts ca ON ca.id = tr.source_account_id
        WHERE tr.credit_entry_id = t.id)
WHERE t.movement_kind = 'TRANSFER_IN'
  AND EXISTS (SELECT 1 FROM transfers tr WHERE tr.credit_entry_id = t.id);

/*
 * A RETURN NAMES THE PAYEE WHO DID NOT GET IT, not the account the money came back to.
 * Naming the holder here would produce "returned from yourself"; what the customer needs
 * to know is which payment failed.
 */
UPDATE account_transactions t
SET counterparty_mask = (
        SELECT ca.masked_number
        FROM transfers tr
                 JOIN customer_accounts ca ON ca.id = tr.destination_account_id
        WHERE tr.reversal_entry_id = t.id),
    counterparty_name = (
        SELECT COALESCE(
                       (SELECT co.name FROM companies co WHERE co.id = ca.company_id),
                       (SELECT cu.full_name FROM customers cu WHERE cu.id = ca.customer_id))
        FROM transfers tr
                 JOIN customer_accounts ca ON ca.id = tr.destination_account_id
        WHERE tr.reversal_entry_id = t.id)
WHERE t.movement_kind = 'TRANSFER_RETURNED'
  AND EXISTS (SELECT 1 FROM transfers tr WHERE tr.reversal_entry_id = t.id);

-- ------------------------------------------------------------------ the invariant

/*
 * BOTH OR NEITHER, and added LAST so the backfill above runs before it is enforced.
 *
 * A name with no mask cannot be told apart from a namesake; a mask with no name is the
 * masked-account-number statement line this migration exists to replace. Either half
 * alone is a half-written row, and the constraint means no future code path can leave one
 * behind - not a rule in a service that a new endpoint could forget.
 */
ALTER TABLE account_transactions
    ADD CONSTRAINT account_transactions_counterparty_complete
        CHECK ((counterparty_name IS NULL AND counterparty_mask IS NULL)
            OR (counterparty_name IS NOT NULL AND counterparty_mask IS NOT NULL));

COMMENT ON COLUMN account_transactions.counterparty_name IS
    'The other party''s name AS IT WAS when this entry was posted. Null for a movement with no other party, such as a deposit or an opening balance. Never updated: a statement records a moment, and the name belongs to that moment.';

COMMENT ON COLUMN account_transactions.counterparty_mask IS
    'The other party''s masked account number, e.g. **** 7898. Never the full number.';

COMMENT ON COLUMN account_transactions.movement_kind IS
    'What this movement was: CASH, TRANSFER_OUT, TRANSFER_IN or TRANSFER_RETURNED. The server names it so that screens do not have to infer it from the direction, which cannot tell an arrival from a refund.';
