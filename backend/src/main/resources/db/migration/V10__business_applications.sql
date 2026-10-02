-- Company registration, which until now existed only in the browser.
--
-- WHAT WAS ACTUALLY WRONG, because it is worth recording precisely.
--
-- The five-step business wizard — company details, signatories, documents, review,
-- submit — was answered end to end by the front end's own mock. The applicant reached a
-- page reading "Your application has been received. The bank will review it and contact
-- your named representative", with a reference number, and nothing had been sent
-- anywhere. Staff opened Registrations and saw no such application, because there was no
-- such application: the backend had no company registration at all.
--
-- A false receipt is worse than an error. An error sends somebody to a branch; a receipt
-- sends them home to wait for a call that was never going to come.
--
-- THIS MIRRORS PERSONAL REGISTRATION and deliberately adds nothing to it. An application
-- is a REQUEST, not a company and not an account. Nothing here is verified by this
-- service: an administrator reads it against the bank's own records, and a manager's
-- approval is where a human takes responsibility for having checked. The columns below
-- hold claims.
--
-- WHAT IS STILL OWED, recorded in frontend/docs/OPEN-ITEMS.md rather than guessed at:
-- which documents a company must supply, who may apply on its behalf, how signatories
-- are verified, and whether a corporate mandate needs more than one manager's signature.
-- A company mandate is not one manager's signature, and this schema does not pretend to
-- settle that.

-- ------------------------------------------------------- which flow is in flight

/*
 * `email_verifications` now guards two different registrations, so it has to say which.
 *
 * The payload column holds the submitted details as JSON between `start` and `complete`.
 * With one flow the service could simply read it back as personal details; with two, a
 * resend would deserialise a company into a person and fail in a way that looks like a
 * corrupt database rather than a missing discriminator.
 *
 * Defaulted to PERSONAL for the rows already in flight when this migration runs — which
 * is correct, because PERSONAL is the only flow that existed to create them.
 */
ALTER TABLE email_verifications
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'PERSONAL';

ALTER TABLE email_verifications
    ADD CONSTRAINT email_verifications_kind_check
        CHECK (kind IN ('PERSONAL', 'BUSINESS'));

-- ------------------------------------------------------------ the application

/*
 * One row per business application, alongside the `applications` row rather than inside
 * it.
 *
 * `applications` holds what every registration has: a reference, a status, who to
 * contact, whether that address was proven. Adding a dozen nullable company columns to it
 * would mean every personal registration carrying a TIN column it can never use, and the
 * next registration kind adding another dozen.
 *
 * The contact — not the company — is the email and phone on the parent row, because that
 * is who the bank writes to. `display_name` there is the company name, because that is
 * what staff scan a queue by.
 */
CREATE TABLE business_applications (
    application_id           UUID         NOT NULL PRIMARY KEY
                                 REFERENCES applications (id) ON DELETE CASCADE,

    company_name             VARCHAR(200) NOT NULL,

    /* As issued by RDB. Shape is not validated: this service has no register to check
       it against, and a format guess here would turn away a real company. */
    registration_number      VARCHAR(60)  NOT NULL,

    /*
     * The tax identification number, and it is DELIBERATELY NOT UNIQUE.
     *
     * A unique constraint here would refuse a second application for the same TIN, and
     * the refusal would be visible on a public, unauthenticated form — an existence
     * oracle telling anyone who asks which companies bank at Zigama. Rwandan TINs are
     * enumerable, so that is a ready-made target list for phishing a company's
     * signatories, and commercially sensitive besides.
     *
     * Two applications for one TIN instead sit in the staff queue, where a person can
     * see both sets of details and decide. Nothing is lost except the leak.
     */
    tin                      VARCHAR(40)  NOT NULL,

    business_type            VARCHAR(80)  NOT NULL,
    sector                   VARCHAR(80)  NOT NULL,
    address                  VARCHAR(400) NOT NULL,

    /* The company's own switchboard and inbox, which are not the named contact's. */
    company_email            VARCHAR(254) NOT NULL,
    company_phone            VARCHAR(32)  NOT NULL,

    /* An account the company already holds, if it said so. Optional, and unverified
       like everything else here. */
    existing_account_number  VARCHAR(34),

    /* The person the bank deals with. Their email is also on the parent row, because
       that is the address the code was sent to and the approval will go to. */
    contact_full_name        VARCHAR(160) NOT NULL,
    contact_role             VARCHAR(120) NOT NULL,

    created_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX business_applications_tin_idx ON business_applications (tin);

COMMENT ON COLUMN business_applications.tin IS
    'Not unique, on purpose: a duplicate refused on a public form would tell anyone '
    'which companies bank here. Duplicates go to the staff queue instead.';

-- -------------------------------------------------------------- signatories

/*
 * The people the company says may act on the account.
 *
 * A table rather than a JSON column, because these are the rows a manager reads one at a
 * time while deciding, and because each will eventually need its own verification state
 * once the bank says how signatories are checked. `ordinal` keeps the order the
 * applicant listed them in — the first is usually the primary signatory, and losing that
 * ordering loses information nobody can recover.
 *
 * NOT VERIFIED. A national ID here is a number somebody typed. Nothing in this service
 * checks it, and no screen may show it as confirmed.
 */
CREATE TABLE business_signatories (
    id              UUID         NOT NULL PRIMARY KEY,

    application_id  UUID         NOT NULL
                        REFERENCES applications (id) ON DELETE CASCADE,

    /* The order the applicant listed them in. Named `ordinal` rather than `position`
       because POSITION is a reserved function name in the SQL standard — PostgreSQL
       tolerates it as a column, H2 need not, and the tests run on H2. */
    ordinal         SMALLINT     NOT NULL,

    full_name       VARCHAR(160) NOT NULL,
    role            VARCHAR(120) NOT NULL,
    national_id     VARCHAR(40)  NOT NULL,
    email           VARCHAR(254),
    phone           VARCHAR(32),

    CONSTRAINT business_signatories_ordinal_unique UNIQUE (application_id, ordinal)
);

CREATE INDEX business_signatories_application_idx
    ON business_signatories (application_id);

-- ---------------------------------------------------------------- documents

/*
 * THE FILES ARE NOT HERE, and this table exists to say so out loud.
 *
 * The wizard's Documents step collects file names and nothing else — there is no upload
 * endpoint and no storage. Recording the names is still worth doing: an administrator
 * can see what the applicant believed they had attached and ask for the ones that
 * matter, which is strictly better than a queue of applications with no idea what
 * paperwork exists.
 *
 * `received` is false on every row this service can currently create. It is a column
 * rather than an assumption so that the day an upload endpoint lands, the applications
 * submitted before it are still distinguishable from the ones after — and so nothing
 * downstream can quietly read a promised document as a held one.
 *
 * What the bank still has to decide before real upload: which documents are required,
 * how long they are kept, and who may read them. Company documents are sensitive.
 */
CREATE TABLE business_documents (
    id              UUID         NOT NULL PRIMARY KEY,

    application_id  UUID         NOT NULL
                        REFERENCES applications (id) ON DELETE CASCADE,

    /* The file name as the applicant's browser reported it. Display only — never used
       to open, write or serve anything. */
    file_name       VARCHAR(260) NOT NULL,

    received        BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX business_documents_application_idx
    ON business_documents (application_id);

COMMENT ON COLUMN business_documents.received IS
    'False for every row this service can create: the wizard collects names, not files. '
    'A column rather than an assumption, so promised documents can never be read as held.';
