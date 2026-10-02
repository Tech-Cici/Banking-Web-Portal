-- Corporate customers: a company, the people who may act for it, and its accounts.
--
-- WHAT THIS UNBLOCKS. An administrator could open a company application and see
-- everything on it, and then had nowhere to go: creating a login was refused, because
-- this service had no corporate customer of any kind. A customer had no type, there was
-- no company, and the session handed every signed-in person the same fixed retail
-- permission set. The staff screen used to show the account form anyway and the button
-- always failed.
--
-- THE ACCOUNTS BELONG TO THE COMPANY, NOT TO A PERSON, and that is the decision this
-- schema exists to express. A company's money is not the finance director's money. If a
-- company account hung off a customer row, then removing that person's access would
-- orphan the company's accounts, and a second signatory would need their own copy of
-- every account — two records of one balance, which is the thing this codebase refuses
-- everywhere else.
--
-- So visibility runs through MEMBERSHIP: a person sees a company's accounts because they
-- are a member of that company, and stops seeing them when that membership ends. Nothing
-- about the accounts changes.
--
-- WHAT IS DELIBERATELY NOT HERE, and is recorded in frontend/docs/OPEN-ITEMS.md rather
-- than guessed at: per-signatory limits, maker/checker thresholds, and how a second
-- signatory is verified before being given access. The named contact on the application
-- becomes the company's first administrator and is the only member this service creates.
-- A mandate with several signatories at different limits is a decision the bank owes.

-- ------------------------------------------------------------------ companies

CREATE TABLE companies (
    id                  UUID         NOT NULL PRIMARY KEY,

    /*
     * The application it was created from. One company per approved application.
     *
     * UNIQUE so that pressing "create" twice cannot produce two companies for one
     * application — the service checks the status first, but a constraint holds under a
     * double-submitted form and a retried request in a way an application-level check
     * does not.
     */
    application_id      UUID         NOT NULL UNIQUE
                            REFERENCES applications (id),

    name                VARCHAR(200) NOT NULL,

    /*
     * The short code an administrator shares with colleagues who want access.
     *
     * Unique, and NOT derived from the company name: a guessable code is a way in. The
     * join-a-business flow is not implemented yet, so nothing consumes this — it is here
     * because the portal's CorporateMembership type already carries it and a company
     * created without one would need backfilling later.
     */
    code                VARCHAR(24)  NOT NULL UNIQUE,

    /* Copied from the application rather than joined, because they are what the bank
       accepted at the moment it accepted it. The application may later be corrected. */
    registration_number VARCHAR(60)  NOT NULL,
    tin                 VARCHAR(40)  NOT NULL,

    created_by          VARCHAR(160) NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX companies_tin_idx ON companies (tin);

-- ------------------------------------------------- what kind of customer this is

/*
 * RETAIL or CORPORATE, defaulted to RETAIL for every row already there — which is
 * correct, because a personal customer is the only kind this service has ever created.
 *
 * A column rather than "has a membership, therefore corporate". The two would usually
 * agree and the day they did not, the disagreement would decide what a customer can see:
 * a retail customer who somehow gained a membership would silently become a corporate
 * one. The type is a decision recorded at creation; the membership is what it grants.
 */
ALTER TABLE customers
    ADD COLUMN user_type VARCHAR(16) NOT NULL DEFAULT 'RETAIL';

ALTER TABLE customers
    ADD CONSTRAINT customers_user_type_check
        CHECK (user_type IN ('RETAIL', 'CORPORATE'));

-- --------------------------------------------------------------- memberships

/*
 * Who may act for a company, and in what capacity.
 *
 * A table rather than a column on `customers`, because a person can act for more than
 * one company — an accountant with two clients is ordinary — and a company has more than
 * one signatory. Neither is true today (this service creates exactly one membership per
 * company) and both are certain, so the shape is the one that does not need a migration
 * to get there.
 *
 * THE ROLE IS A LABEL, NOT THE AUTHORITY. Permissions are derived from it in one place
 * (SessionController) and the server checks those on every request. A role stored here
 * grants nothing by itself, exactly as a signatory listed on an application grants
 * nothing.
 */
CREATE TABLE corporate_memberships (
    id           UUID         NOT NULL PRIMARY KEY,

    company_id   UUID         NOT NULL REFERENCES companies (id) ON DELETE CASCADE,
    customer_id  UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    role         VARCHAR(16)  NOT NULL,

    granted_by   VARCHAR(160) NOT NULL,
    granted_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * Ended rather than deleted, for the same reason a removed account keeps its row:
     * the first question anyone asks after a wrong access grant is who granted it and
     * when, and a deleted row cannot answer.
     */
    revoked_by      VARCHAR(160),
    revoked_at      TIMESTAMP(6) WITH TIME ZONE,
    revocation_reason VARCHAR(500),

    CONSTRAINT corporate_memberships_role_check
        CHECK (role IN ('ADMIN', 'MAKER', 'APPROVER', 'VIEWER')),

    /* One membership per person per company. A second would double their permissions
       and make "remove their access" ambiguous. */
    CONSTRAINT corporate_memberships_unique UNIQUE (company_id, customer_id)
);

CREATE INDEX corporate_memberships_customer_idx
    ON corporate_memberships (customer_id);

-- ------------------------------------------------- accounts held by a company

/*
 * A company account is a customer_accounts row whose company_id is set.
 *
 * ONE TABLE, NOT TWO. A separate company_accounts table would mean two ledgers, two
 * balance columns and two code paths for a deposit — and account_transactions would have
 * to reference either, which no foreign key can express cleanly. A company account is an
 * account; who holds it is the difference.
 *
 * customer_id stays NOT NULL and records WHO ENTERED IT — the administrator assigns
 * accounts while creating a login, and that login is the anchor. Access is not granted by
 * that column for a company account: it is granted by membership, which is why a
 * colleague added later sees the same accounts without anything being copied.
 */
ALTER TABLE customer_accounts
    ADD COLUMN company_id UUID REFERENCES companies (id);

CREATE INDEX customer_accounts_company_idx ON customer_accounts (company_id);

COMMENT ON COLUMN customer_accounts.company_id IS
    'Set when the account belongs to a company rather than to the person it was entered '
    'against. Access to these runs through corporate_memberships, not through customer_id.';

/*
 * The account-number uniqueness rule has to grow with it.
 *
 * V8 made (customer_id, account_number) unique, which stops one person being given the
 * same account twice. That does not cover a company: two colleagues could each be given
 * the company's account number under their own customer_id, producing two rows for one
 * real account and two balances for one pot of money.
 *
 * So a company's accounts get their own constraint, and V8's stays exactly as it is.
 *
 * NOT A PARTIAL INDEX, and that is a portability decision rather than a style one. The
 * obvious spelling is two filtered indexes — `WHERE company_id IS NULL` and `WHERE
 * company_id IS NOT NULL` — which PostgreSQL supports and H2 does not; the tests run on
 * H2, so it would have failed every one of them. Verified rather than assumed.
 *
 * A plain composite constraint does the job on both, because SQL treats NULLs as
 * distinct: personal rows all have company_id NULL, so this constraint never compares
 * two of them, and V8's constraint is what keeps them unique. Also verified: two
 * personal rows with the same number are accepted, and two rows for one company with the
 * same number are refused.
 */
ALTER TABLE customer_accounts
    ADD CONSTRAINT customer_accounts_company_unique UNIQUE (company_id, account_number);
