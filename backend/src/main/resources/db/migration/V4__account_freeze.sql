-- Freezing an account.
--
-- SUSPENDED was already a legal value in customers_status_check and sign-in already
-- refused it, but nothing could ever set it. These columns are what makes the state
-- reachable and, more importantly, accountable.
--
-- A freeze stops a customer reaching their own money. That is sometimes exactly right —
-- a compromised login, a fraud investigation, a court order — and it is never something
-- that should be possible to do anonymously or without a stated reason. Both are
-- recorded here rather than left to a log that can rotate away.

-- One ALTER per column, deliberately.
--
-- PostgreSQL accepts several ADD COLUMN clauses in a single ALTER TABLE; H2 does not,
-- and the tests run on H2 while production runs on PostgreSQL. Writing it the way both
-- accept is the whole reason the test suite is worth having here: this migration passed
-- review reading correctly and failed on the first test run.

-- The manager who froze it. A NAME, matching created_by and approved_by, so the record
-- survives that person leaving and their staff row being removed.
ALTER TABLE customers ADD COLUMN frozen_by VARCHAR(160);

ALTER TABLE customers ADD COLUMN frozen_at TIMESTAMP(6) WITH TIME ZONE;

-- Why. Required by the service on both freezing and unfreezing, and kept afterwards: the
-- customer will ask, and "the system says frozen" is not an answer anyone at a branch can
-- work with.
ALTER TABLE customers ADD COLUMN freeze_reason VARCHAR(500);

COMMENT ON COLUMN customers.frozen_by IS
    'Manager who last froze or unfroze this account. Retained after unfreezing so the '
    'history is not erased by restoring access.';

COMMENT ON COLUMN customers.freeze_reason IS
    'Why the account was last frozen or unfrozen. Shown to staff, never to the customer '
    '— a freeze reason may concern an investigation the customer must not be tipped off '
    'about.';
