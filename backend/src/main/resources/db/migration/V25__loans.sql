/*
 * LOANS THE BANK HAS GRANTED, AND APPLICATIONS FOR NEW ONES.
 *
 * WHAT WAS THERE BEFORE. Nothing. /loans was answered by the browser's own mock from a
 * fixture array keyed to a mock persona, so a real customer saw either somebody else's
 * sample loan or nothing, and a page reload changed the answer. Same failure as V18, V20
 * and V23.
 *
 * THE PORTAL DOES NOT COMPUTE A LOAN. principal, outstanding_minor, installment_minor and
 * the rate are TYPED BY STAFF from the bank's own systems. This service has no amortisation
 * schedule, no arrears logic and no payoff calculation, and it must not grow one: those are
 * the core banking system's numbers, and a second set computed here would eventually
 * disagree with the bank about what somebody owes.
 *
 * THE SIMULATION IS NOT STORED, anywhere, deliberately. It is an indicative answer to "what
 * would this cost", computed per request from a rate in configuration. Writing it down
 * would turn an estimate into a record, and the next person to read the table would take it
 * for a quote the bank had given.
 *
 * STATUS IS STORED, NEVER INFERRED -- the same rule as V23's cards. Whether a loan is in
 * arrears is a statement the bank makes about a customer's debt, not an arithmetic result
 * of comparing next_due_date to this service's clock.
 */

CREATE TABLE loans (
    id                   UUID         NOT NULL PRIMARY KEY,

    customer_id          UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /* The account it was paid into, and where repayments are taken from. */
    account_id           UUID         NOT NULL REFERENCES customer_accounts (id),

    /* The application this came from, when there was one. Null for a loan granted at a
       branch and recorded afterwards. */
    service_request_id   UUID         REFERENCES service_requests (id),

    /* PERSONAL, SALARY_ADVANCE, BUSINESS -- matching the products in application.yml and
       the three the screen offers. A product the configuration does not know is refused. */
    product              VARCHAR(32)  NOT NULL,

    /* What the customer quotes. Derived from the row id, so it cannot collide. */
    reference            VARCHAR(24)  NOT NULL UNIQUE,

    currency             CHAR(3)      NOT NULL,

    /* ALL THREE TYPED BY STAFF. Nothing in this service derives one from another. */
    principal_minor      BIGINT       NOT NULL,
    outstanding_minor    BIGINT       NOT NULL,
    installment_minor    BIGINT       NOT NULL,

    /*
     * The rate on THIS loan, as agreed, not the published product rate. A customer's
     * actual rate can differ from the advertised one, and showing them the advertised
     * figure would be telling them something untrue about their own debt.
     */
    interest_rate_percent NUMERIC(6,3) NOT NULL,

    next_due_date        DATE         NOT NULL,

    status               VARCHAR(16)  NOT NULL,

    recorded_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    recorded_by_name     VARCHAR(160) NOT NULL,

    CONSTRAINT loan_status_known
        CHECK (status IN ('ACTIVE', 'IN_ARREARS', 'SETTLED')),

    /* A loan of nothing is not a loan. */
    CONSTRAINT loan_principal_positive
        CHECK (principal_minor > 0),

    /* Owing more than was borrowed, or less than nothing, is a typing error worth catching
       at the moment it is typed rather than on the customer's screen. */
    CONSTRAINT loan_outstanding_sane
        CHECK (outstanding_minor >= 0 AND outstanding_minor <= principal_minor),

    CONSTRAINT loan_installment_positive
        CHECK (installment_minor > 0),

    CONSTRAINT loan_rate_sane
        CHECK (interest_rate_percent >= 0 AND interest_rate_percent <= 100),

    /* SETTLED means nothing is owed, and the two must not be able to disagree. */
    CONSTRAINT loan_settled_is_paid
        CHECK ((status = 'SETTLED' AND outstanding_minor = 0)
            OR (status <> 'SETTLED'))
);

CREATE INDEX loans_owner_idx ON loans (customer_id, recorded_at DESC);

/* One loan per application, so recording it twice cannot produce two debts. NOT a partial
   index: V23 records what that costs on H2, where the test suite runs these migrations. */
CREATE UNIQUE INDEX loans_request_idx ON loans (service_request_id);

COMMENT ON TABLE loans IS
    'Loans as staff recorded them from the bank''s own systems. The portal computes no loan figure: no amortisation, no arrears logic, no payoff.';

COMMENT ON COLUMN loans.interest_rate_percent IS
    'The rate on THIS loan as agreed, not the published product rate in application.yml. A customer''s own rate can differ from the advertised one.';

/*
 * APPLYING FOR A LOAN IS A SERVICE REQUEST, reusing V18's table rather than growing a
 * second one. It is the same shape: a customer asks the bank for something, staff act on
 * it, the customer is told. Two tables would mean two queues and two sets of statuses.
 *
 * SAME CHANGE AS V5, V16, V17, V18, V22 AND V24: the enum and this CHECK are two copies of
 * one list, so adding a value to one without the other fails the INSERT rather than
 * producing a missing row.
 */

ALTER TABLE service_requests DROP CONSTRAINT IF EXISTS service_request_type_known;

ALTER TABLE service_requests
    ADD CONSTRAINT service_request_type_known
        CHECK (request_type IN ('CARD', 'CHEQUE_BOOK', 'LOAN_APPLICATION'));
