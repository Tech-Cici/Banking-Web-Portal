-- The two notification kinds a forgotten password produces.
--
-- SAME CHANGE AS V5, FOR THE SAME REASON, AND IT WAS CAUGHT THE SAME WAY.
--
-- OutboxKind gained PASSWORD_REISSUED and PASSWORD_REQUEST_REFUSED when "Forgot
-- password?" was implemented. The enum is persisted by name and outbox_kind_check lists
-- the names, so issuing a password failed at the moment of recording the email - after the
-- new credential had already been written to the customer's row in the same transaction,
-- which rolled the whole re-issue back with a 500.
--
-- A Java enum and a CHECK constraint are two copies of one list, and adding a value to
-- one is half a change. V5 said exactly that, and this is the next time it happened; what
-- stopped it reaching anybody was OutboxKindsArePersistableTest, which writes one row per
-- enum value and therefore fails the moment the two lists disagree.
--
-- A separate migration rather than an edit to V5: V5 has already run on every database
-- that exists and Flyway records a checksum of the file it ran.

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
                        'PASSWORD_REQUEST_REFUSED'));
