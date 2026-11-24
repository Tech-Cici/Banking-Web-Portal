/*
 * TELLING THE PERSON WHO RECEIVED THE MONEY.
 *
 * WHAT WAS THERE BEFORE. Nothing. TransferService.approve notified the sender and nobody
 * else, so a customer whose account was credited learned it by opening the account and
 * reading the ledger -- or did not learn it at all. "Has it arrived yet" is the single
 * most asked question about a transfer and the portal had no answer for the one party who
 * most needs it.
 *
 * WHY THIS FILE EXISTS AT ALL, AND WHY ADDING THE ENUM VALUE ALONE IS HALF A CHANGE.
 * NotificationKind and the CHECK below are two copies of one list. Notifications are
 * written inside the caller's transaction, deliberately -- so a kind missing from this
 * constraint does not produce a missing notification, it fails the INSERT and rolls back
 * the release that had already posted to the ledger. A manager would press Approve and
 * get a 500 while the money stayed in flight. V5, V16, V17 and V18 are the same change for
 * the same reason, and NotificationKindsArePersistableTest is what catches it.
 *
 * POSTGRES CANNOT ADD A VALUE TO A CHECK IN PLACE, so it is dropped and rewritten whole.
 * IF EXISTS because a database created before V20 named the constraint differently.
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
                        'PASSWORD_CHANGED',
                        'ACCOUNT_UNFROZEN'));

/*
 * NO BACKFILL. Every transfer already released happened without this notification, and a
 * row dated today about money that arrived three weeks ago is worse than silence: the
 * panel exists to answer "has anything happened that I did not know about", and seeding it
 * with old events makes every future row less believable.
 */
