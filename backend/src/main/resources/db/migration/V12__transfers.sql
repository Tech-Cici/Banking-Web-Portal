-- Transfers between accounts in this portal, held until a manager approves them.
--
-- WHAT A TRANSFER IS HERE. One customer instructs money to move from an account they may
-- act on to another account in this same service. A manager decides. Approved, the
-- beneficiary is credited; rejected, the sender is made whole.
--
-- INTERNAL ONLY, and the schema says so rather than the code: destination_account_id is a
-- foreign key to customer_accounts. There is no column for a bank name, a SWIFT code or a
-- mobile wallet, because this service has no rail to send on. A transfer to another bank
-- would have to be recorded as sent and could never be confirmed as arrived, and a row
-- that can hold a destination nobody can reach is a row somebody will eventually mark as
-- COMPLETED. The portal's Beneficiary type already carries DOMESTIC, INTERNATIONAL and
-- WALLET; none of them can be expressed here until there is something to send on.
--
-- ------------------------------------------------------------------------------------
-- THE HOLD IS A LEDGER ENTRY. This is the decision worth reading twice.
--
-- Submitting a transfer DEBITS the sender immediately, as a real row in
-- account_transactions. Approval credits the beneficiary. Rejection credits the sender
-- back. So there are two or three entries per transfer and no fourth state.
--
-- The alternative was a `held_minor` column on customer_accounts, with no entry until
-- approval. It is rejected because it breaks the one rule the whole ledger rests on:
-- V9 states that customer_accounts.balance_minor is the sum of its entries and that a
-- balance which can move without an entry is a number nobody can explain. A hold is
-- money the customer can no longer spend; representing it anywhere other than the ledger
-- means the balance and the entries disagree by design, and the customer's own statement
-- would not show why their money is unavailable.
--
-- Debiting at submission rather than at approval is also what makes "never negative"
-- true. Held only at approval, a customer could submit their whole balance five times
-- over and a manager could approve all five — four of them failing AFTER a manager had
-- already said yes, which is the worst moment for a payment to fail.
--
-- WHAT THIS COSTS, stated plainly: between submission and a decision the money is in
-- flight. The sum of all balances is short by the total of everything pending, so the
-- books balance only as
--
--     sum(customer_accounts.balance_minor) + sum(pending transfers) = constant
--
-- A bank would hold that difference in a suspense account and this service does not have
-- one. The invariant above is asserted in TransferTest instead, so a code path that
-- loses money in flight fails a test rather than being discovered in a reconciliation.
-- A suspense account is recorded in frontend/docs/OPEN-ITEMS.md as owed.
--
-- WHAT IS DELIBERATELY NOT HERE: no fee column, because the bank has not set a fee and a
-- zero would look like a decision; no threshold, because every transfer needs approval
-- for now and a threshold is a config value rather than a column; no scheduled or
-- recurring transfers, which are a different thing with their own failure modes.

CREATE TABLE transfers (
    id                     UUID         NOT NULL PRIMARY KEY,

    /*
     * Where the money comes from, and who instructed it.
     *
     * The account and the customer are recorded separately on purpose. For a company
     * account they are not the same thing: the account belongs to the company and the
     * instruction came from one named member of it, which is exactly what anybody
     * reviewing a company's payments needs to know. Entitlement to the source account is
     * checked by AccountAccess.mayAct at submission; this column is the record of who
     * passed that check.
     */
    source_account_id      UUID         NOT NULL
                               REFERENCES customer_accounts (id),
    submitted_by           UUID         NOT NULL REFERENCES customers (id),

    /*
     * The beneficiary, as an account in this service.
     *
     * Resolved from the FULL account number the customer typed, which is never returned
     * to any client — the id is what travels. NOT NULL, so a transfer cannot exist
     * without somewhere for the money to arrive.
     */
    destination_account_id UUID         NOT NULL
                               REFERENCES customer_accounts (id),

    amount_minor           BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,

    /* What the customer wrote on it. Shown to the beneficiary on their statement. */
    reference              VARCHAR(140) NOT NULL,

    status                 VARCHAR(20)  NOT NULL,

    submitted_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * IDEMPOTENCY, on the instruction rather than only on the ledger entry.
     *
     * The case: the customer taps Send, the money is held, and the response is lost on a
     * bad connection. The app retries with the same key. Without this the same
     * instruction is submitted twice and the sender is debited twice — and unlike a
     * duplicated deposit, the second one sits in a manager's queue looking legitimate.
     */
    idempotency_key        VARCHAR(80)  NOT NULL UNIQUE,

    /*
     * The manager's decision. Their NAME, not their id — the audit has to survive a
     * member of staff leaving and their row being removed, which is the same choice
     * customers.approved_by makes.
     */
    decided_by             VARCHAR(160),
    decided_at             TIMESTAMP(6) WITH TIME ZONE,

    /* Required when rejected. A refused payment with no stated reason is one nobody can
       explain to the customer who is about to telephone about it. */
    rejection_reason       VARCHAR(500),

    /*
     * The entries this transfer produced, so the instruction and the money are linked in
     * both directions.
     *
     * Without these, reconciling a transfer against the ledger means matching on amount
     * and timestamp, which stops working the moment two identical transfers happen in the
     * same second. The debit exists from submission; the credit only once approved; the
     * reversal only once rejected.
     */
    debit_entry_id         UUID         REFERENCES account_transactions (id),
    credit_entry_id        UUID         REFERENCES account_transactions (id),
    reversal_entry_id      UUID         REFERENCES account_transactions (id),

    CONSTRAINT transfers_status_check
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),

    /* Zero moves nothing and would sit in a manager's queue asking them to approve it. */
    CONSTRAINT transfers_amount_positive CHECK (amount_minor > 0),

    /*
     * An account cannot pay itself. It would produce a debit and a credit of the same
     * amount on one account — a pair of entries that nets to nothing while occupying a
     * manager's attention and a customer's statement.
     */
    CONSTRAINT transfers_not_self CHECK (source_account_id <> destination_account_id),

    /*
     * A decision has a decider and a date, and an undecided transfer has neither. Written
     * as a constraint because "approved by nobody" is the state an audit cannot use, and
     * it is reachable by any code path that sets the status without the rest.
     */
    CONSTRAINT transfers_decision_complete CHECK (
        (status = 'PENDING_APPROVAL' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING_APPROVAL' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),

    /* A rejection states why; an approval has nothing to explain. */
    CONSTRAINT transfers_rejection_reasoned CHECK (
        (status = 'REJECTED' AND rejection_reason IS NOT NULL)
        OR (status <> 'REJECTED' AND rejection_reason IS NULL)
    ),

    /*
     * The money follows the status, enforced by the database.
     *
     * Pending: debited, nothing else. Approved: the beneficiary was credited. Rejected:
     * the sender was made whole. A row claiming APPROVED with no credit entry is a
     * transfer a screen would report as arrived while the beneficiary's balance says
     * otherwise — the exact shape of manufactured-success this codebase refuses.
     */
    CONSTRAINT transfers_entries_match_status CHECK (
        (status = 'PENDING_APPROVAL'
             AND credit_entry_id IS NULL AND reversal_entry_id IS NULL)
        OR (status = 'APPROVED'
             AND credit_entry_id IS NOT NULL AND reversal_entry_id IS NULL)
        OR (status = 'REJECTED'
             AND credit_entry_id IS NULL AND reversal_entry_id IS NOT NULL)
    )
);

/* The customer's own list, newest first. */
CREATE INDEX transfers_source_idx ON transfers (source_account_id, submitted_at DESC);

/* What arrived, for the beneficiary's own history. */
CREATE INDEX transfers_destination_idx ON transfers (destination_account_id, submitted_at DESC);

/*
 * The manager's queue.
 *
 * Status first so the index serves "everything still waiting" — which is the only query
 * the queue screen makes, and the one that must stay fast as decided transfers pile up
 * behind it. Not a partial index WHERE status = 'PENDING_APPROVAL': PostgreSQL supports
 * it and H2, where the tests run, does not. Verified when V11 needed the same thing.
 */
CREATE INDEX transfers_queue_idx ON transfers (status, submitted_at);
