/*
 * GRANTING A LOAN IS NOT COLLECTING ONE.
 *
 * WHAT WAS WRONG. A loan application went through the same staff path as a cheque book: mark
 * it ready, type where the customer should collect it, email them that it is waiting. That
 * vocabulary is right for a book of cheques and wrong for a loan in every part. Nobody walks
 * into a branch to pick up a loan; the money is paid into the account they named when they
 * applied. The staff screen was asking a question with no sensible answer, and the only
 * endings available to an application were COLLECTED or DECLINED -- so a granted loan would
 * have been recorded as something the customer came and fetched.
 *
 * THREE LISTS GROW HERE, for the three things granting a loan now produces.
 *
 *   GRANTED            a loan application ends granted or declined, never collected
 *   LOAN_DISBURSEMENT  the credit that puts the money in the account is its own kind of
 *                      movement, not a vague CASH entry on a statement
 *   LOAN_GRANTED       the customer is told in the portal, not only by email
 *
 * Same change as V5, V16, V17, V18 and V22, for the same reason: each enum and its CHECK are
 * two copies of one list, and a value added to the code without the constraint fails at
 * insert time, in a transaction, with a message about a constraint rather than about a loan.
 */

/* ------------------------------------------------------- how a loan application can end */

ALTER TABLE service_requests DROP CONSTRAINT IF EXISTS service_request_status;

ALTER TABLE service_requests
    ADD CONSTRAINT service_request_status
        CHECK (status IN ('SUBMITTED', 'READY', 'COLLECTED', 'GRANTED', 'DECLINED'));

COMMENT ON COLUMN service_requests.status IS
    'SUBMITTED, then one of: READY and COLLECTED for a thing the customer picks up, GRANTED for a loan that was approved and paid out, DECLINED for anything refused.';

/* ---------------------------------------------------------- what the disbursement looks like */

/*
 * ITS OWN MOVEMENT KIND rather than CASH. A statement that calls a loan "cash" tells the
 * customer their balance rose and nothing about why, and the line is the single most
 * important one on the statement: it is the moment they went into debt.
 */
ALTER TABLE account_transactions DROP CONSTRAINT IF EXISTS account_transactions_movement_kind_check;

ALTER TABLE account_transactions
    ADD CONSTRAINT account_transactions_movement_kind_check
        CHECK (movement_kind IN ('CASH',
                                 'TRANSFER_OUT',
                                 'TRANSFER_IN',
                                 'TRANSFER_RETURNED',
                                 'LOAN_DISBURSEMENT'));

/* ------------------------------------------------------------------ telling the customer */

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
                        'LOAN_GRANTED',
                        'PASSWORD_CHANGED',
                        'ACCOUNT_UNFROZEN'));

/*
 * NO BACKFILL, for the reason V22 gives. Nothing has been granted yet, and if anything had,
 * a notification dated today about a loan paid out last month would be worse than silence.
 */
