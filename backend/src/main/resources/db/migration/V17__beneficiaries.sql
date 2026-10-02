/*
 * SAVED PAYEES, AND THE STAFF CHECK THAT LETS ONE BE PAID.
 *
 * WHAT WAS THERE BEFORE. Nothing. The Beneficiaries screen was served entirely by the
 * browser's mock: adding a payee created a row marked PENDING_VERIFICATION, and no code
 * anywhere - no screen, no endpoint, no timer - ever moved it to ACTIVE. The customer was
 * told the payee was "in its cooling-off period" and it stayed in that state permanently.
 * The copy named two different controls, a clock and a verification, and implemented
 * neither.
 *
 * Worse than the dead end: the gate was not enforced where it mattered. The standing-order
 * screen filtered its payee list to ACTIVE, but the TRANSFER screen filtered only on payee
 * TYPE - so an unverified payee was selectable and payable on the one screen that moves
 * money, which is exactly the "add this account and send the money now" script the delay is
 * supposed to break.
 *
 * WHAT THIS IS. A payee is created PENDING, a member of bank staff looks at it, and nothing
 * can be sent to it until they approve it. That is a person, not a clock: the bank asked
 * for staff approval rather than an elapsed-time window.
 *
 * WHAT MAKES THE CHECK A CHECK RATHER THAN A RUBBER STAMP. The queue shows the name the
 * customer typed NEXT TO the name the bank holds for that account number, resolved at
 * review time, and flags whether they match. A staff member looking only at what the
 * customer typed has nothing to compare it against, and an approval screen that cannot
 * verify anything is theatre that costs the customer a wait. A mismatch is the single most
 * common sign of both a mistyped digit and a payee somebody has been talked into adding.
 *
 * THAT COMPARISON ONLY EXISTS FOR AN ACCOUNT AT THIS BANK, which is stated here rather
 * than discovered later. A payee at another bank, abroad, or on a mobile wallet is held by
 * an institution this service cannot ask, so there is no name to put beside what the
 * customer typed - and the queue says exactly that instead of showing a blank that reads
 * like a pass.
 *
 * EITHER ROLE MAY APPROVE, and that does not weaken the four-eyes rule. ADMIN and MANAGER
 * are deliberately disjoint for ACCOUNT creation - an admin creates, a manager releases,
 * so issuing credentials takes two people. Here the MAKER IS THE CUSTOMER and bank staff
 * are the checker, so the separation holds whichever staff role clears it. Payee
 * verification is also the kind of volume work that must not queue behind the one role that
 * releases money.
 *
 * THE FULL ACCOUNT NUMBER IS STORED, and that is the point of a saved payee: it is the
 * payment address the customer does not want to retype. It is never returned - every API
 * response carries the mask only, the same rule TransfersController states - and it is what
 * the transfer path reads server-side once the payee is ACTIVE.
 */

CREATE TABLE beneficiaries (
    id                  UUID         NOT NULL PRIMARY KEY,

    /*
     * WHOSE PAYEE THIS IS. Scoped to the customer, never global: two customers paying the
     * same landlord get a row each, because one of them may be refused and the other
     * approved, and because a shared row would let one customer's list disclose another's.
     */
    customer_id         UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /* What the CUSTOMER called them. Not evidence of anything - it is free text they typed,
       and comparing it with the real holder's name is the whole job of the review. */
    name                VARCHAR(160) NOT NULL,

    /*
     * WHICH RAILS THE PAYMENT WOULD TAKE: INTERNAL, DOMESTIC, INTERNATIONAL or WALLET.
     * The four names are the front end's own vocabulary, copied rather than improved -
     * renaming one here would make the enum, this constraint and the TypeScript union
     * three copies of a list that already has two.
     *
     * ONLY INTERNAL IS PAYABLE TODAY, and only INTERNAL can be name-checked. The other
     * three are other institutions: this service has no connection to any of them, so
     * the outbound transfer screens say so instead of showing a form, and there is no
     * holder name for it to resolve. A payee on one of those rails can still be saved -
     * a customer may be getting ready - and the queue tells the reviewer plainly that
     * the comparison is unavailable rather than passed.
     */
    beneficiary_type    VARCHAR(16)  NOT NULL,

    /* The bank or wallet, for anything not held here. */
    provider            VARCHAR(120) NOT NULL,

    /*
     * The payment address, in full, and never in a response. A saved payee with only a mask
     * is not a payee: masks are not unique, so paying one would mean choosing whose money
     * arrives.
     */
    account_number      VARCHAR(34)  NOT NULL,

    /* Stored rather than derived at read time so that every response, log line and audit
       row shows the same mask, including after the number's format changes. */
    masked_destination  VARCHAR(24)  NOT NULL,

    currency            VARCHAR(3)   NOT NULL,

    /*
     * PENDING until staff act. ACTIVE once approved, which is the only state money may be
     * sent to. REFUSED when staff decline it, with the reason the customer is shown and
     * emailed.
     */
    status              VARCHAR(20)  NOT NULL,

    added_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    reviewed_at         TIMESTAMP(6) WITH TIME ZONE,
    reviewed_by_name    VARCHAR(160),

    refused_reason      VARCHAR(300),

    /*
     * WHAT THE REVIEWER SAW. The holder name resolved at the moment of the decision, kept
     * because it is the evidence the decision rested on: re-resolving it later answers a
     * different question, since an account can change hands. Null for a payee at another
     * bank, where this service has no name to resolve - and that null is itself worth
     * seeing in the queue, because it tells the reviewer their comparison is unavailable
     * rather than passed.
     */
    verified_holder_name VARCHAR(160),

    CONSTRAINT beneficiary_status
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'REFUSED')),

    CONSTRAINT beneficiary_type_known
        CHECK (beneficiary_type IN ('INTERNAL', 'DOMESTIC', 'INTERNATIONAL', 'WALLET')),

    /*
     * A reviewed payee carries WHEN and BY WHOM, a pending one carries neither. Without
     * this, a code path that approves a payee and forgets to name the reviewer leaves an
     * audit trail saying a payment address was cleared and not who cleared it.
     */
    CONSTRAINT beneficiary_reviewed_together
        CHECK ((status = 'PENDING_VERIFICATION' AND reviewed_at IS NULL AND reviewed_by_name IS NULL)
            OR (status <> 'PENDING_VERIFICATION' AND reviewed_at IS NOT NULL AND reviewed_by_name IS NOT NULL)),

    CONSTRAINT beneficiary_refusal_reasoned
        CHECK ((status = 'REFUSED' AND refused_reason IS NOT NULL)
            OR (status <> 'REFUSED' AND refused_reason IS NULL))
);

/*
 * The customer's own list, newest first, and the staff queue's "everything pending, oldest
 * first". Two different orders over the same rows, so two indexes.
 */
CREATE INDEX beneficiaries_owner_idx
    ON beneficiaries (customer_id, added_at);

CREATE INDEX beneficiaries_queue_idx
    ON beneficiaries (status, added_at);

/*
 * ONE PAYEE PER CUSTOMER PER DESTINATION, enforced in the service rather than here, for the
 * same reason V15 gives: the correct tool is a partial unique index on
 * (customer_id, account_number) WHERE status <> 'REFUSED', PostgreSQL has it, and H2 - which
 * the test suite runs on - does not support a WHERE clause on an index. A constraint that
 * exists in production and not in the tests is worse than one that exists in neither,
 * because it is the tests that stop telling the truth.
 *
 * A REFUSED payee is excluded from that rule on purpose: a customer who mistyped a digit,
 * was refused, and wants to add the corrected number must be able to.
 */

COMMENT ON TABLE beneficiaries IS
    'Saved payees. A payee is PENDING_VERIFICATION until bank staff compare the name the customer typed with the account holder the bank holds; only an ACTIVE payee can be paid.';

COMMENT ON COLUMN beneficiaries.account_number IS
    'The full payment address. Never returned by any API - responses carry masked_destination only.';

COMMENT ON COLUMN beneficiaries.verified_holder_name IS
    'The account holder name the bank resolved at review time, kept as the evidence the decision rested on. Null where this service holds no name, which the queue shows as an unavailable comparison rather than a pass.';

/*
 * THE TWO NOTIFICATION KINDS A REVIEWED PAYEE PRODUCES.
 *
 * SAME CHANGE AS V5 AND V16, FOR THE SAME REASON. A Java enum and a CHECK constraint are
 * two copies of one list, and adding a value to one is half a change: OutboxKind gains
 * BENEFICIARY_APPROVED and BENEFICIARY_REFUSED here, and without the constraint below
 * approving a payee would fail at the moment of recording the email - after the payee had
 * already been marked ACTIVE in the same transaction, rolling the whole approval back with
 * a 500. That is exactly how V16 was found. OutboxKindsArePersistableTest writes one row
 * per enum value and fails the moment the two lists disagree.
 *
 * Done in this migration rather than a later one because the kinds and the table they serve
 * are one change; V16 was separate only because V5 had already run everywhere.
 */

ALTER TABLE outbox DROP CONSTRAINT IF EXISTS outbox_kind_check;

ALTER TABLE outbox
    ADD CONSTRAINT outbox_kind_check
        CHECK (kind IN ('EMAIL_VERIFICATION',
                        'ACCOUNT_CREATED',
                        'ACCOUNT_APPROVED',
                        'ACCOUNT_REJECTED',
                        'ACCOUNT_FROZEN',
                        'ACCOUNT_UNFROZEN',
                        'PASSWORD_REISSUED',
                        'PASSWORD_REQUEST_REFUSED',
                        'BENEFICIARY_APPROVED',
                        'BENEFICIARY_REFUSED'));
