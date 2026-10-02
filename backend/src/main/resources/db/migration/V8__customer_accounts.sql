-- The customer's accounts, as the administrator entered them.
--
-- THE FLOW THIS IMPLEMENTS, because the previous two attempts implemented something else.
--
-- An administrator opens a registration, and against Zigama's own records — which they are
-- already looking at, and which this system has no view of — types the accounts that person
-- holds: a current account, a savings account, whichever apply, each with its number. They
-- press "create account". A manager approves the login. The customer signs in and sees
-- exactly those accounts.
--
-- That is one step, done by the administrator, at the moment the login is created.
--
-- WHAT V6 AND V7 GOT WRONG, and why they are dropped rather than kept.
--
--   V6 recorded "account opening requests" sitting at AWAITING_CORE_BANKING, which the
--   customer never saw. But the accounts already exist at the bank; nothing is being
--   opened, and there is nothing to wait for.
--
--   V7 added a separate manager-only step for typing account numbers after the fact. That
--   is a second screen, a second person and a second visit for something the administrator
--   already has in front of them.
--
-- Both tables are dropped. Neither has ever run against a database — the service has not
-- been rebuilt since V5 — so nothing is lost, and carrying two unused tables forward would
-- leave the next reader guessing which of the three is real. IF EXISTS so this is correct
-- whether or not they were ever applied.

DROP TABLE IF EXISTS account_opening_requests;
DROP TABLE IF EXISTS linked_accounts;

CREATE TABLE customer_accounts (
    id              UUID         NOT NULL PRIMARY KEY,

    customer_id     UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /*
     * THE REAL ACCOUNT NUMBER, and every read of this column is sensitive.
     *
     * Stored because it is what identifies the account at the bank, and because a
     * statement or a transfer will need it. It must never leave the service: the API
     * returns masked_number, the logs record masked_number, and no email, URL or error
     * message carries this column.
     */
    account_number  VARCHAR(34)  NOT NULL,

    /*
     * Derived once, at entry, and stored rather than computed per request — so there is
     * exactly one place the masking rule is applied, and no code path that renders the raw
     * number by forgetting to call a helper.
     */
    masked_number   VARCHAR(24)  NOT NULL,

    account_type    VARCHAR(40)  NOT NULL,
    currency        CHAR(3)      NOT NULL,

    /*
     * The administrator who entered it. THIS IS THE VERIFICATION RECORD. Nothing in this
     * service checks that the account exists or belongs to this person; the administrator
     * checked it against the bank's records, and their name here is what says so — exactly
     * as the approving manager's name is the record that the identity was checked.
     */
    assigned_by     VARCHAR(160) NOT NULL,
    assigned_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * Removing keeps the row. Showing one customer another's account is the most serious
     * mistake this screen can make, and the record of who entered it and who caught it is
     * worth more than a tidy table.
     */
    removed_by      VARCHAR(160),
    removed_at      TIMESTAMP(6) WITH TIME ZONE,
    removal_reason  VARCHAR(500),

    /*
     * NO BALANCE COLUMN, and there must never be one. The money is in core banking. A
     * figure kept here would be a second, unreconciled copy that no teller ever sees, and
     * a stale balance in a banking portal is indistinguishable from a live one until
     * somebody acts on it.
     */

    CONSTRAINT customer_accounts_type_check
        CHECK (account_type IN ('CURRENT', 'SAVINGS', 'FIXED_DEPOSIT', 'SALARY',
                                'JUNIOR_SAVINGS', 'TARGET_SAVINGS', 'LOAN_SERVICING')),

    CONSTRAINT customer_accounts_unique UNIQUE (customer_id, account_number)
);

CREATE INDEX customer_accounts_customer_idx ON customer_accounts (customer_id);

/* To answer "does another customer already hold this account?" without a scan. */
CREATE INDEX customer_accounts_number_idx ON customer_accounts (account_number);

COMMENT ON COLUMN customer_accounts.account_number IS
    'SENSITIVE. The real account number. Never returned by the API and never logged — '
    'masked_number is what leaves this table.';
