/*
 * A LOAN APPLICATION'S TERMS, AS NUMBERS.
 *
 * WHAT WAS WRONG. A loan application was stored the way a cheque book request is: one
 * sentence in `details`, two hundred characters, built for a human to read --
 *
 *     "personal loan, RWF 500000 over 24 months, into **** 0001 - school fees"
 *
 * That is fine for a queue screen and useless for granting. The amount, the product and the
 * term are in there, but as English. The only ways to get them back out are to parse the
 * sentence or to have staff retype them, and both decide how much money to lend by a route
 * that can be wrong without being noticed. A purpose containing the word "months" is enough
 * to break the first; a mistyped digit is enough to break the second.
 *
 * So the terms become columns. `details` stays as it is -- it is what the queue screen reads
 * and it carries the customer's own words for the purpose, which no column replaces.
 *
 * NULLABLE, BUT NOT OPTIONAL. They are null for every other kind of request, and a cheque
 * book with an interest rate would be nonsense. The constraint below ties them to the request
 * type in both directions, so "nullable" means "belongs to loans only" rather than "might be
 * missing".
 *
 *
 * EXISTING APPLICATIONS ARE DECLINED, and that is the honest option rather than the tidy one.
 *
 * An application raised before this migration has no terms and no way to recover them. The
 * alternatives were to guess them out of the sentence, or to leave the rows exempt from the
 * constraint for ever and let staff meet an un-grantable application with no explanation.
 * Declining says what is true: this cannot be processed, here is why, please ask again. The
 * customer is not silently left in a queue that cannot move, and -- because an open
 * application blocks a new one on the same account -- it also unblocks them.
 *
 * ALREADY-FINISHED APPLICATIONS ARE LEFT ALONE. A COLLECTED or DECLINED row is history, and
 * rewriting history to satisfy a constraint added afterwards would be the worst of both. The
 * constraint exempts terminal rows for exactly that reason.
 */

ALTER TABLE service_requests ADD COLUMN loan_product      VARCHAR(32);
ALTER TABLE service_requests ADD COLUMN loan_amount_minor BIGINT;
ALTER TABLE service_requests ADD COLUMN loan_currency     CHAR(3);
ALTER TABLE service_requests ADD COLUMN loan_months       INTEGER;

/* ------------------------------------------------- applications that cannot be granted */

UPDATE service_requests
   SET status           = 'DECLINED',
       declined_at      = CURRENT_TIMESTAMP,
       /*
        * NOT A PERSON'S NAME. This column records who decided, and nobody did; writing a
        * staff member's name here would put a decision in somebody's mouth. The audit trail
        * should read as what happened.
        */
       declined_by_name = 'Automatic (migration V29)',
       decline_reason   = 'This application was made before the portal recorded loan terms as figures, so it cannot be processed. Please apply again.'
 WHERE request_type = 'LOAN_APPLICATION'
   AND status IN ('SUBMITTED', 'READY');

/* ------------------------------------------------------------------------ the invariants */

/*
 * TERMS BELONG TO LOAN APPLICATIONS, AND ONLY TO THEM.
 *
 * Both directions are stated. A loan application that is still live must carry its figures,
 * so none can reach a staff screen un-grantable. Anything that is not a loan application must
 * carry none, so a cheque book cannot acquire an amount by a careless insert.
 *
 * TERMINAL ROWS ARE EXEMPT, which is what lets the history above survive. A COLLECTED or
 * DECLINED application is a record of something that already happened and nothing will read
 * its terms again.
 */
ALTER TABLE service_requests ADD CONSTRAINT service_request_loan_terms
    CHECK (
        (request_type = 'LOAN_APPLICATION'
             AND (status IN ('COLLECTED', 'DECLINED')
                  OR (loan_product IS NOT NULL
                      AND loan_amount_minor IS NOT NULL
                      AND loan_currency IS NOT NULL
                      AND loan_months IS NOT NULL)))
        OR (request_type <> 'LOAN_APPLICATION'
             AND loan_product IS NULL
             AND loan_amount_minor IS NULL
             AND loan_currency IS NULL
             AND loan_months IS NULL)
    );

/* Zero is not an amount and zero months is not a term. Both are ways of writing a loan that
   looks like a loan and is not one. */
ALTER TABLE service_requests ADD CONSTRAINT service_request_loan_amount_positive
    CHECK (loan_amount_minor IS NULL OR loan_amount_minor > 0);

ALTER TABLE service_requests ADD CONSTRAINT service_request_loan_months_positive
    CHECK (loan_months IS NULL OR loan_months > 0);

COMMENT ON COLUMN service_requests.loan_amount_minor IS
    'What the customer asked to borrow, in minor units. Null for every request that is not a loan application. Staff may grant a different figure; this is the ask, not the decision.';
