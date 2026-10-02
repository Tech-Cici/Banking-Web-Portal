-- What the branch asked to have opened, recorded rather than discarded.
--
-- THE BUG THIS CLOSES.
--
-- The admin screen collected an account type, a currency, an opening balance and a branch,
-- and posted them. The endpoint's signature was createAccount(@PathVariable UUID id,
-- Authentication caller) — no body parameter — so Spring read the path and dropped the
-- JSON on the floor. The administrator chose "Current account", saw a success message, and
-- nothing was stored anywhere. There was no table that could have held it.
--
-- That is the worst shape a defect can take in a bank: the screen agreed with the operator.
-- A branch that had taken a real opening deposit and typed the figure in would have been
-- told it worked.
--
-- WHAT THIS TABLE IS, AND IS NOT.
--
-- It is a RECORD OF A REQUEST. It is not an account, it holds no balance, and nothing in
-- this service can make an account exist — the real ones live in the core banking system,
-- which is not connected. A row here means "a named administrator asked, on this date, for
-- this kind of account in this currency". That is a true and useful thing to keep; a
-- balance would not be.
--
-- ONE ROW PER ACCOUNT, deliberately. A customer opening a current account and a savings
-- account has two rows, not one row saying CURRENT_AND_SAVINGS. The compound spelling
-- cannot express closing one of them, cannot be counted, and needs a new value for every
-- pairing. A foreign-currency account is likewise not a type: it is one of these types with
-- a currency that is not RWF.

CREATE TABLE account_opening_requests (
    id            UUID         NOT NULL PRIMARY KEY,

    -- The login this was requested alongside. Cascades, because a request to open an
    -- account for a customer who no longer exists is not a record worth orphaning.
    customer_id   UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    account_type  VARCHAR(40)  NOT NULL,

    -- ISO 4217. Three characters, and the CHECK is the cheapest guard against a screen
    -- sending a display label like "Rwandan franc" into a column reports will group by.
    currency      CHAR(3)      NOT NULL,

    -- Where the request came from. A name, matching created_by elsewhere, so it survives
    -- a branch being renamed or a staff row being removed.
    branch        VARCHAR(160) NOT NULL,

    requested_by  VARCHAR(160) NOT NULL,
    requested_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * AWAITING_CORE_BANKING is the only status this service can set, and saying so in the
     * schema is the point: nothing here advances it. When core banking is connected, that
     * integration marks a row OPENED or DECLINED. Until then a row sitting in this state
     * is the honest answer to "what happened to my request?" — it was recorded and nobody
     * has acted on it.
     */
    status        VARCHAR(30)  NOT NULL,

    CONSTRAINT account_opening_requests_type_check
        CHECK (account_type IN ('CURRENT', 'SAVINGS', 'FIXED_DEPOSIT', 'SALARY',
                                'JUNIOR_SAVINGS', 'TARGET_SAVINGS', 'LOAN_SERVICING')),

    CONSTRAINT account_opening_requests_status_check
        CHECK (status IN ('AWAITING_CORE_BANKING', 'OPENED', 'DECLINED'))
);

CREATE INDEX account_opening_requests_customer_idx
    ON account_opening_requests (customer_id);

COMMENT ON TABLE account_opening_requests IS
    'What a branch asked to have opened. Not an account and not a balance — this service '
    'issues logins, and core banking owns accounts.';
