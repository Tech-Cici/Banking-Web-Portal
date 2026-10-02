-- The two notification kinds a freeze produces.
--
-- WHY THIS IS A SEPARATE MIGRATION AND NOT AN EDIT TO V1.
--
-- V1 has already run on every database that exists, and Flyway records a checksum of the
-- file it ran. Editing it in place makes the next startup fail validation against a
-- database that is otherwise perfectly correct. Adding to an applied migration is never
-- the fix, however small the change looks.
--
-- WHY IT WAS NEEDED AT ALL.
--
-- OutboxKind gained ACCOUNT_FROZEN and ACCOUNT_UNFROZEN when freezing was implemented.
-- The enum is persisted by name, and outbox_kind_check listed the four original names, so
-- every freeze failed at the point of recording the notification — after the account had
-- already been suspended in the same transaction, which rolled the freeze back with a 500.
--
-- A Java enum and a CHECK constraint are two copies of one list. Adding a value to one is
-- half a change. OutboxKindsArePersistableTest now enumerates the enum and writes one row
-- per value, so the next addition fails in the test suite rather than in a manager's face.

ALTER TABLE outbox DROP CONSTRAINT IF EXISTS outbox_kind_check;

ALTER TABLE outbox
    ADD CONSTRAINT outbox_kind_check
        CHECK (kind IN ('EMAIL_VERIFICATION',
                        'ACCOUNT_CREATED',
                        'ACCOUNT_APPROVED',
                        'ACCOUNT_REJECTED',
                        'ACCOUNT_FROZEN',
                        'ACCOUNT_UNFROZEN'));
