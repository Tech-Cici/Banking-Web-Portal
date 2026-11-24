/*
 * STANDING ORDERS -- A REPEATING PAYMENT THE CUSTOMER SETS UP ONCE.
 *
 * WHAT WAS THERE BEFORE. Nothing on the server. /standing-orders was answered by the
 * browser's mock, so an order survived until the page was reloaded and then stopped
 * existing. The same failure as V18 and V20.
 *
 * THESE RUN WITHOUT A MANAGER RELEASING THEM, AND THAT IS A DELIBERATE EXCEPTION.
 * Everywhere else in this system money leaves an account only after a manager approves it
 * -- see V12 and TransferService. A standing order debits and credits on its due date with
 * no human in the loop. That was decided knowingly; the alternative was for a due order to
 * create a PENDING transfer in the manager's queue like any other.
 *
 * SO THE GUARDS MOVE INTO THE DATA. last_run_on records what has actually been paid, and
 * the ledger's own idempotency key for an occurrence is derived from this row's id and the
 * due date -- which is what makes a second sweep over the same date a no-op rather than a
 * second payment. A scheduler that fires twice, a container that wakes and catches up, a
 * manual re-run: none of them can pay twice.
 *
 * A RUN THAT CANNOT BE FUNDED IS RECORDED, NOT RETRIED. last_failure holds why, the
 * customer is notified, and next_run_on advances to the following period. Retrying until
 * money appears would mean a payment landing at a moment nobody chose, which is worse than
 * a missed one the customer was told about.
 */

CREATE TABLE standing_orders (
    id                  UUID         NOT NULL PRIMARY KEY,

    customer_id         UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    source_account_id   UUID         NOT NULL REFERENCES customer_accounts (id),

    /* The saved payee. Must be ACTIVE when the order is created -- an order against a
       payee still waiting verification would pay somebody nobody has checked. */
    beneficiary_id      UUID         NOT NULL REFERENCES beneficiaries (id),

    /*
     * The account the money actually lands in, resolved from the payee ONCE, when the order
     * is created, and stored. Resolving it on every run would mean a payee edited later
     * silently redirects a standing payment -- which is precisely the attack this bank's
     * payee verification exists to stop.
     */
    destination_account_id UUID      NOT NULL REFERENCES customer_accounts (id),

    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,

    /* The customer's own text, quoted on every payment this order makes. */
    reference           VARCHAR(140) NOT NULL,

    frequency           VARCHAR(16)  NOT NULL,

    /* DATES, not timestamps. "The 5th of the month" is a date; storing an instant would
       make the payment drift across a day boundary with the server's time zone. */
    next_run_on         DATE         NOT NULL,
    ends_on             DATE,

    status              VARCHAR(16)  NOT NULL,

    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /* What has actually been paid. The anchor for "has this occurrence already run". */
    last_run_on         DATE,
    last_failure        VARCHAR(300),

    CONSTRAINT standing_order_frequency_known
        CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'QUARTERLY')),

    CONSTRAINT standing_order_status_known
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),

    /* Zero or negative is not a payment. The application refuses it first; this is the
       backstop that holds if a later code path forgets to. */
    CONSTRAINT standing_order_amount_positive
        CHECK (amount_minor > 0),

    CONSTRAINT standing_order_ends_after_start
        CHECK (ends_on IS NULL OR ends_on >= next_run_on OR status = 'ENDED')
);

/* The customer's own list. */
CREATE INDEX standing_orders_owner_idx ON standing_orders (customer_id, created_at DESC);

/* The sweep: everything active and due, oldest first. */
CREATE INDEX standing_orders_due_idx ON standing_orders (status, next_run_on);

COMMENT ON TABLE standing_orders IS
    'Repeating payments. These execute WITHOUT manager approval, unlike every other way money leaves an account here; the idempotency of a run is what takes the place of that control.';

COMMENT ON COLUMN standing_orders.destination_account_id IS
    'Resolved from the payee once, at creation, and frozen. Re-resolving on each run would let a later edit to the payee redirect an existing standing payment.';

COMMENT ON COLUMN standing_orders.last_run_on IS
    'The last occurrence actually paid. With the row id it forms the ledger idempotency key, which is what stops a repeated sweep paying twice.';

/*
 * THE TWO NOTIFICATION KINDS THIS PRODUCES.
 *
 * Same change as V5, V16, V17, V18 and V22, for the same reason: NotificationKind and this
 * CHECK are two copies of one list, notifications are written inside the caller's
 * transaction, and a kind missing here does not lose a notification -- it rolls back the
 * payment that had already posted to the ledger.
 *
 * BOTH OUTCOMES ARE NOTIFIED, and the failed one matters more. Money leaving on a schedule
 * with nobody approving it means the customer is the only check there is; a payment that
 * silently did not happen is the thing they must hear about.
 */

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_kind_known;

ALTER TABLE notifications
    ADD CONSTRAINT notifications_kind_known
        CHECK (kind IN ('SERVICE_REQUEST_READY',
                        'SERVICE_REQUEST_DECLINED',
                        'BENEFICIARY_APPROVED',
                        'BENEFICIARY_REFUSED',
                        'TRANSFER_RELEASED',
                        'TRANSFER_REJECTED',
                        'TRANSFER_RECEIVED',
                        'STANDING_ORDER_PAID',
                        'STANDING_ORDER_FAILED',
                        'PASSWORD_CHANGED',
                        'ACCOUNT_UNFROZEN'));
