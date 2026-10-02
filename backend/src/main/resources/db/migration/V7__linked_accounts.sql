-- Real accounts, linked to a login by a manager.
--
-- WHAT THIS FIXES. The staff screen said "None linked yet" against every customer that
-- has ever existed, because account_masks was a hardcoded empty list and nothing in this
-- service could ever produce an account number. That was honest but permanent: there was
-- no route by which an account could become linked.
--
-- WHAT A ROW MEANS. A manager, having checked Zigama's own records, states that this
-- customer holds this account. It is a human assertion with a name and a timestamp
-- against it, exactly like the approval that precedes it — this service still cannot see
-- core banking, and an entry here is a claim rather than a synchronised fact.
--
-- WHAT IT IS NOT. Not a balance, and not an account this service opened. The money lives
-- in core banking. When that integration exists, these rows become the set it reconciles
-- against, and mismatches will matter.

CREATE TABLE linked_accounts (
    id              UUID         NOT NULL PRIMARY KEY,

    customer_id     UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /*
     * THE REAL ACCOUNT NUMBER. Treat every read of this column as sensitive.
     *
     * It is stored because matching a customer to their account in core banking needs the
     * whole number, and a mask cannot do that. It must never leave the service: the API
     * returns masked_number, the logs record masked_number, and no email, URL or error
     * message carries this column. The service-layer comment on LinkedAccountEntity says
     * the same thing where a developer will actually meet it.
     */
    account_number  VARCHAR(34)  NOT NULL,

    /*
     * Derived once, at link time, and stored rather than computed per request. Every
     * screen and log line reads THIS column, so there is exactly one place the masking
     * rule is applied and no code path that can accidentally render the raw number by
     * forgetting to call a helper.
     */
    masked_number   VARCHAR(24)  NOT NULL,

    account_type    VARCHAR(40)  NOT NULL,
    currency        CHAR(3)      NOT NULL,

    linked_by       VARCHAR(160) NOT NULL,
    linked_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * Unlinking keeps the row. A link that turns out to be wrong is the most serious
     * mistake this screen can make — it shows one person another's account — so the
     * record of who made it and who undid it is worth more than a tidy table.
     */
    unlinked_by     VARCHAR(160),
    unlinked_at     TIMESTAMP(6) WITH TIME ZONE,
    unlink_reason   VARCHAR(500),

    CONSTRAINT linked_accounts_type_check
        CHECK (account_type IN ('CURRENT', 'SAVINGS', 'FIXED_DEPOSIT', 'SALARY',
                                'JUNIOR_SAVINGS', 'TARGET_SAVINGS', 'LOAN_SERVICING')),

    /*
     * The same account cannot be linked to the same customer twice. Linking it to a
     * DIFFERENT customer is refused in the service rather than here, because the check has
     * to ignore rows that were already unlinked — and because the refusal deserves a
     * sentence a manager can act on rather than a constraint violation.
     */
    CONSTRAINT linked_accounts_unique UNIQUE (customer_id, account_number)
);

CREATE INDEX linked_accounts_customer_idx ON linked_accounts (customer_id);

/*
 * To answer "is this account already linked to somebody else?" without scanning. The
 * lookup is on the full number because that is the only thing that identifies an account;
 * the mask is not unique and never will be.
 */
CREATE INDEX linked_accounts_number_idx ON linked_accounts (account_number);

COMMENT ON COLUMN linked_accounts.account_number IS
    'SENSITIVE. The real account number, stored for matching against core banking. Never '
    'returned by the API, never logged — masked_number is what leaves this table.';
