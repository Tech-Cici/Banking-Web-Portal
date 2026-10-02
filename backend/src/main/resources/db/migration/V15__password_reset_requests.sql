/*
 * A CUSTOMER ASKING FOR A NEW PASSWORD.
 *
 * Until now "Forgot password?" on the sign-in screen led to a page saying the service was
 * not available online and to call the bank. That is a dead end on the one screen where a
 * customer is already stuck, and it is the commonest reason anybody contacts a bank about
 * internet banking.
 *
 * WHAT THIS IS NOT. It is not a self-service reset: there is no token in an email and no
 * page that sets a new password from a link. The customer asks, the request appears in the
 * staff portal, and a MANAGER re-issues a temporary password through exactly the machinery
 * that issues one at approval - emailed, short-lived, and refused for everything except
 * the change-password screen until it has been replaced.
 *
 * That is a deliberate choice rather than a shortcut. A self-service reset link is a
 * second credential-bearing path into every account in the bank, with its own token store,
 * its own expiry and single-use rules, and its own email. This flow adds no new way to
 * obtain a credential at all: it adds a queue in front of the one that already exists and
 * is already tested, and keeps a human in the loop for an action whose whole purpose is to
 * hand somebody else's password out. The trade is that a reset is not instant, and the
 * bank's own staff already expect to be in this conversation.
 *
 * NOTHING IS WRITTEN FOR AN ADDRESS THAT IS NOT A CUSTOMER'S, and the API answers the
 * same either way. A table that recorded every address typed into the form would be a
 * list of guesses - and a queue that showed staff "somebody tried bob@example.com" would
 * tell them, and anybody reading over their shoulder, which addresses do not bank here.
 */

CREATE TABLE password_reset_requests (
    id              UUID         NOT NULL PRIMARY KEY,

    customer_id     UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    requested_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * PENDING until a manager acts. FULFILLED when a temporary password has been issued;
     * REFUSED when the manager decides it should not be - a caller who could not answer
     * the branch's questions, or a request the customer says they never made.
     */
    status          VARCHAR(16)  NOT NULL,

    settled_at      TIMESTAMP(6) WITH TIME ZONE,
    settled_by_name VARCHAR(160),

    /*
     * WHY IT WAS REFUSED. Required for a refusal and forbidden otherwise, by the
     * constraint below: a refused reset with no stated reason is one nobody can explain to
     * the customer who rings back, and "the bank said no" is the answer that makes a
     * second call certain.
     */
    refused_reason  VARCHAR(300),

    CONSTRAINT password_reset_status
        CHECK (status IN ('PENDING', 'FULFILLED', 'REFUSED')),

    /*
     * A settled request carries WHEN and BY WHOM, a pending one carries neither. Without
     * this, a code path that marks a request done and forgets to name the manager leaves
     * an audit trail that says a password was handed out and not who did it.
     */
    CONSTRAINT password_reset_settled_together
        CHECK ((status = 'PENDING' AND settled_at IS NULL AND settled_by_name IS NULL)
            OR (status <> 'PENDING' AND settled_at IS NOT NULL AND settled_by_name IS NOT NULL)),

    CONSTRAINT password_reset_refusal_reasoned
        CHECK ((status = 'REFUSED' AND refused_reason IS NOT NULL)
            OR (status <> 'REFUSED' AND refused_reason IS NULL))
);

/*
 * The queue screen's query is "every pending request, oldest first", and the service's
 * duplicate check is "has this customer got one pending". Both are served by this.
 *
 * ONE PENDING REQUEST PER CUSTOMER IS ENFORCED IN THE SERVICE, not by a partial unique
 * index on (customer_id) WHERE status = 'PENDING'. That index is the right tool and
 * PostgreSQL has it; H2, which the test suite runs on, does not support a WHERE clause on
 * an index. A constraint that exists in production and not in the tests is worse than one
 * that exists in neither, because it is the tests that would stop telling the truth.
 */
CREATE INDEX password_reset_requests_queue_idx
    ON password_reset_requests (status, requested_at);

CREATE INDEX password_reset_requests_customer_idx
    ON password_reset_requests (customer_id);

COMMENT ON TABLE password_reset_requests IS
    'Customers who have asked for a new password. A manager re-issues a temporary password through the same path used at approval; there is no self-service reset link.';

COMMENT ON COLUMN password_reset_requests.status IS
    'PENDING, FULFILLED (a temporary password was issued) or REFUSED (with a reason).';
