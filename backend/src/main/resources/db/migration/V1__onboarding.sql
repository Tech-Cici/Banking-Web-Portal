-- Onboarding: registration applications, the customers created from them, email
-- verification codes, bank staff, and a record of every message the bank sent.
--
-- Deliberate choices worth knowing before changing anything here:
--
--  * No password column on `applications`. An applicant does not choose one. The bank
--    issues a temporary password when an administrator creates the account, and the
--    customer replaces it on first sign-in. Two passwords on one account is a state
--    nobody can reason about.
--
--  * `email_verifications.code_hash`, never `code`. A verification code is a credential
--    for as long as it lives. Storing it in clear means a database dump, a read replica
--    or an over-broad SELECT hands out working codes for every registration in flight.
--
--  * `outbox` records what was sent, with no code or password in the body of an approval.
--    It is the audit trail for "did the bank actually tell this customer?", which is the
--    question asked when a customer says they never heard back.

CREATE TABLE applications (
    id                  UUID         PRIMARY KEY,
    reference           VARCHAR(32)  NOT NULL UNIQUE,
    kind                VARCHAR(20)  NOT NULL,
    status              VARCHAR(24)  NOT NULL,

    display_name        VARCHAR(160) NOT NULL,
    email               VARCHAR(254) NOT NULL,
    phone               VARCHAR(32)  NOT NULL,

    -- Proven by entering a code sent to that address. The admin sees this before
    -- creating a login, because everything afterwards is delivered by email.
    email_verified      BOOLEAN      NOT NULL DEFAULT FALSE,

    account_number      VARCHAR(40),
    national_id         VARCHAR(40),
    date_of_birth       DATE,

    submitted_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- Set when an administrator creates the login.
    customer_id         UUID,
    -- Set when a manager turns it down. Required by the endpoint, so a rejection can
    -- always be explained to the applicant.
    rejection_reason    VARCHAR(500),

    CONSTRAINT applications_kind_check
        CHECK (kind IN ('PERSONAL', 'BUSINESS', 'JOIN_BUSINESS')),
    CONSTRAINT applications_status_check
        CHECK (status IN ('SUBMITTED', 'ACCOUNT_CREATED', 'APPROVED', 'REJECTED'))
);

CREATE INDEX applications_status_idx ON applications (status, submitted_at);
CREATE INDEX applications_email_idx  ON applications (email);


CREATE TABLE customers (
    id                     UUID         PRIMARY KEY,
    application_id         UUID         NOT NULL REFERENCES applications (id),

    full_name              VARCHAR(160) NOT NULL,
    email                  VARCHAR(254) NOT NULL UNIQUE,
    phone                  VARCHAR(32)  NOT NULL,
    customer_number        VARCHAR(24)  NOT NULL UNIQUE,

    status                 VARCHAR(20)  NOT NULL,

    -- BCrypt. Never the password itself, and never reversible.
    password_hash          VARCHAR(100) NOT NULL,
    -- True until the customer replaces the password an administrator issued. While true
    -- the session is refused everywhere except the change-password endpoint.
    must_change_password   BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Who did what, for the four-eyes rule. Names rather than ids so the audit survives
    -- a staff member leaving and their row being removed.
    created_by             VARCHAR(160) NOT NULL,
    created_at             TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    approved_by            VARCHAR(160),
    approved_at            TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT customers_status_check
        CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'SUSPENDED')),
    -- The four-eyes rule, in the schema rather than only in code. A row where one person
    -- both created and approved cannot be written at all, whatever a future service
    -- method forgets to check.
    CONSTRAINT customers_four_eyes_check
        CHECK (approved_by IS NULL OR approved_by <> created_by)
);

CREATE INDEX customers_status_idx ON customers (status, created_at);


CREATE TABLE email_verifications (
    id               UUID         PRIMARY KEY,
    email            VARCHAR(254) NOT NULL,

    -- SHA-256 of the code. See the note at the top of this file.
    code_hash        CHAR(64)     NOT NULL,

    -- The registration details, held server-side between `start` and `complete` so the
    -- applicant cannot alter their national id after the code was sent.
    payload          TEXT         NOT NULL,

    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- Counted so a code cannot be brute-forced. 1,000,000 combinations falls in minutes
    -- to an unlimited guesser.
    attempts         SMALLINT     NOT NULL DEFAULT 0,

    -- Set once the code has been used. A verification token is single-use.
    consumed_at      TIMESTAMP(6) WITH TIME ZONE,
    -- Issued at verification, exchanged at `complete`.
    token            VARCHAR(64)  UNIQUE
);

CREATE INDEX email_verifications_email_idx   ON email_verifications (email, created_at);
CREATE INDEX email_verifications_expiry_idx  ON email_verifications (expires_at);


CREATE TABLE staff (
    id             UUID         PRIMARY KEY,
    full_name      VARCHAR(160) NOT NULL,
    email          VARCHAR(254) NOT NULL UNIQUE,
    role           VARCHAR(20)  NOT NULL,
    branch         VARCHAR(80)  NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,

    CONSTRAINT staff_role_check CHECK (role IN ('ADMIN', 'MANAGER'))
);


CREATE TABLE outbox (
    id         UUID         PRIMARY KEY,
    kind       VARCHAR(32)  NOT NULL,
    sender     VARCHAR(254) NOT NULL,
    recipient  VARCHAR(254) NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    body       TEXT         NOT NULL,
    sent_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- Whether it actually left the building, or was only recorded because sending is
    -- switched off. Conflating the two is how a bank believes it notified someone.
    delivered  BOOLEAN      NOT NULL,
    -- Why not, when it did not. A safe message; never a stack trace.
    failure    VARCHAR(500),

    CONSTRAINT outbox_kind_check
        CHECK (kind IN ('EMAIL_VERIFICATION', 'ACCOUNT_CREATED', 'ACCOUNT_APPROVED',
                        'ACCOUNT_REJECTED'))
);

CREATE INDEX outbox_sent_idx      ON outbox (sent_at DESC);
CREATE INDEX outbox_recipient_idx ON outbox (recipient);
