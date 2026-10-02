-- Balances, and the entries that produce them.
--
-- WHAT CHANGED, AND WHAT IT COMMITS THE BANK TO.
--
-- Until now this service held no money: it recorded which accounts a customer holds and
-- said "Not available" where a balance would go, because the ledger was core banking's.
-- The bank has decided this service holds the balances — the administrator enters an
-- opening balance, and the customer deposits and withdraws here.
--
-- That makes this a LEDGER, and a ledger has one non-negotiable property: the balance is
-- the sum of its entries. Every movement is a row in account_transactions, including the
-- opening balance, and customer_accounts.balance_minor is a running total that must equal
-- that sum. A balance that can be set without an entry is a number nobody can explain, and
-- the first time it disagrees with the entries there is no way to know which is right.
--
-- IF CORE BANKING ALSO HOLDS THESE BALANCES, THE TWO WILL DIVERGE. Nothing here
-- reconciles them. That is recorded in frontend/docs/OPEN-ITEMS.md as the bank's decision
-- to make, not a gap to close in code.

-- ------------------------------------------------------------------ balance

/*
 * MINOR UNITS, as a whole number. Never a floating-point type: 0.1 + 0.2 is not 0.3 in
 * binary floating point, and a bank that adds a hundred such amounts is wrong by an amount
 * somebody eventually notices.
 *
 * The scale comes from the currency, and it is NOT always 2 — RWF has no minor unit, so
 * 1000 RWF is 1000 here, not 100000. See Money.scaleOf.
 */
ALTER TABLE customer_accounts ADD COLUMN balance_minor BIGINT NOT NULL DEFAULT 0;

/*
 * A balance may not go negative. There is no overdraft product, so an account that could
 * go below zero would be lending money nobody approved.
 *
 * Enforced here as well as in the service because it is the kind of rule a future code
 * path forgets: the constraint cannot be bypassed by a new endpoint, a repair script or a
 * direct UPDATE at three in the morning.
 */
ALTER TABLE customer_accounts
    ADD CONSTRAINT customer_accounts_balance_not_negative CHECK (balance_minor >= 0);

/*
 * WHEN THE BALANCE LAST MOVED, which the portal shows beside it.
 *
 * Nullable, and read as "when the account was entered" while it is null. A default of
 * "now" would say the balance was confirmed at the moment of this migration, which nobody
 * did — and "as of" is exactly the field a customer uses to decide whether a figure is
 * current enough to act on. Better absent than confidently wrong.
 */
ALTER TABLE customer_accounts ADD COLUMN balance_as_of TIMESTAMP(6) WITH TIME ZONE;

-- ------------------------------------------------------------- the entries

CREATE TABLE account_transactions (
    id                  UUID         NOT NULL PRIMARY KEY,

    account_id          UUID         NOT NULL
                            REFERENCES customer_accounts (id) ON DELETE CASCADE,

    /* CREDIT adds, DEBIT removes. Both are stored positive; the direction says which. */
    direction           VARCHAR(10)  NOT NULL,

    amount_minor        BIGINT       NOT NULL,

    /*
     * The balance AFTER this entry, recorded rather than recomputed.
     *
     * It makes every row independently checkable: a statement can be read without
     * replaying the whole history, and a disagreement between this column and the running
     * sum is a loud, findable inconsistency rather than a silent one.
     */
    balance_after_minor BIGINT       NOT NULL,

    currency            CHAR(3)      NOT NULL,

    description         VARCHAR(200) NOT NULL,

    /* Who caused it: the customer's id, or a staff member's name for an opening balance. */
    performed_by        VARCHAR(160) NOT NULL,

    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * THE IDEMPOTENCY KEY, and it is the single most important column in this table.
     *
     * A customer taps "deposit", the response is lost to a flaky connection, and the app
     * retries. Without this the money is posted twice. With it, the second request finds
     * the first entry and returns it unchanged.
     *
     * Unique across the whole table rather than per account: the client generates one key
     * per submission, and a key that arrived against the wrong account is a bug worth
     * failing loudly on rather than quietly accepting.
     */
    idempotency_key     VARCHAR(80)  NOT NULL,

    CONSTRAINT account_transactions_direction_check
        CHECK (direction IN ('CREDIT', 'DEBIT')),

    /* No zero or negative movements: both are ways of writing a transaction that does nothing
       while looking like it did something. */
    CONSTRAINT account_transactions_amount_positive CHECK (amount_minor > 0),

    CONSTRAINT account_transactions_balance_not_negative CHECK (balance_after_minor >= 0),

    CONSTRAINT account_transactions_idempotency_unique UNIQUE (idempotency_key)
);

CREATE INDEX account_transactions_account_idx
    ON account_transactions (account_id, created_at DESC);

COMMENT ON COLUMN account_transactions.idempotency_key IS
    'One per client submission. A retry after a lost response finds the original entry '
    'instead of posting the money twice.';

COMMENT ON COLUMN account_transactions.balance_after_minor IS
    'The balance once this entry was applied. Recorded so a statement is readable without '
    'replaying history, and so a drift from the running total is findable.';
