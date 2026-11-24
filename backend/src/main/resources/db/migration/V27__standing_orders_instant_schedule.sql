/*
 * STANDING ORDERS: AN INSTANT SCHEDULE, AND A DESTINATION THE CUSTOMER ALREADY OWNS.
 *
 * TWO CHANGES, ONE MIGRATION, because each is unusable without the other: an order that
 * pays every two minutes is only testable if it can also be aimed at an account that
 * exists, and the own-account destination is only reachable once beneficiary_id stops
 * being mandatory.
 *
 *
 * 1. WHY next_run_on BECOMES AN INSTANT.
 *
 * V24 argued for DATE and the argument was right for what it covered: "the 5th of the
 * month" is a date, and storing it as an instant invites the payment to drift across a day
 * boundary with whatever zone the server is set to. That reasoning does not survive a
 * period shorter than a day. A two-minute order has no date to be on.
 *
 * The drift V24 was protecting against is handled here by naming the zone instead of
 * relying on the type: every date-shaped period (weekly, monthly, quarterly) is computed
 * as start-of-day in Africa/Kigali and stored as that exact instant. Kigali is UTC+2 all
 * year with no daylight saving, so "the 5th" is one unambiguous moment, and a server in
 * any zone computes the same one. StandingOrderFrequency is the only place that conversion
 * happens.
 *
 * ends_on STAYS A DATE, deliberately. "This ends on 31 December" really is a date, nothing
 * compares it at finer resolution, and converting it would be a change with no question
 * behind it.
 *
 *
 * 2. WHY beneficiary_id BECOMES NULLABLE.
 *
 * A standing order now moves money between accounts the same customer holds -- the current
 * account into savings, which is the sweep this bank's customers actually want. That
 * destination is chosen by account id from the customer's own list, so there is no saved
 * payee involved and nothing to resolve.
 *
 * NULLABLE RATHER THAN DROPPED. Paying a saved payee is a real feature that this schema
 * already describes correctly, and it is coming back. Dropping the column would throw away
 * a working design to save one nullable field, and would make the return a migration that
 * invents it again.
 *
 * THE COLUMN THIS QUIETLY REPAIRS IS destination_account_id. It used to be resolved from
 * the payee's account number by taking the first account that carried that number -- and
 * since V26 one customer number carries several accounts, one per product, "the first" was
 * whichever row the database felt like returning. Frozen onto the order at creation, that
 * mistake then repeated every period, forever. Choosing the destination by id removes the
 * lookup rather than correcting it: there is no number to resolve and so nothing to guess.
 */

/* ------------------------------------------------------- the payee becomes optional */

ALTER TABLE standing_orders ALTER COLUMN beneficiary_id DROP NOT NULL;

COMMENT ON COLUMN standing_orders.beneficiary_id IS
    'The saved payee, when the order pays one. NULL for a sweep between two accounts the same customer holds, where the destination is chosen by id and no payee exists.';

/* ------------------------------------------------------------ the schedule, in instants */

ALTER TABLE standing_orders ADD COLUMN next_run_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE standing_orders ADD COLUMN last_run_at TIMESTAMP(6) WITH TIME ZONE;

/*
 * START-OF-DAY IN KIGALI, not in the server's zone and not in UTC. An existing order due
 * on the 5th must stay due on the 5th as the bank's customers experience it; UTC midnight
 * would move a 02:00 payment to the 4th for anybody reading it locally.
 */
UPDATE standing_orders
   SET next_run_at = CAST(next_run_on AS TIMESTAMP) AT TIME ZONE 'Africa/Kigali';

UPDATE standing_orders
   SET last_run_at = CAST(last_run_on AS TIMESTAMP) AT TIME ZONE 'Africa/Kigali'
 WHERE last_run_on IS NOT NULL;

ALTER TABLE standing_orders ALTER COLUMN next_run_at SET NOT NULL;

/*
 * THE CHECK GOES AND IS NOT REPLACED, which is a loss worth stating rather than hiding.
 *
 * It compared ends_on to next_run_on, two dates. Comparing a date to an instant needs a
 * time zone conversion, and in PostgreSQL that conversion is STABLE rather than IMMUTABLE
 * -- the time zone database can change underneath it -- so it cannot appear in a CHECK at
 * all. The invariant now lives only in StandingOrderService.create, which already enforced
 * it first. One guard instead of two.
 */
ALTER TABLE standing_orders DROP CONSTRAINT standing_order_ends_after_start;

/* The index has to go before the column it is built on. */
DROP INDEX standing_orders_due_idx;

ALTER TABLE standing_orders DROP COLUMN next_run_on;
ALTER TABLE standing_orders DROP COLUMN last_run_on;

/* The sweep: everything active and due, oldest first. */
CREATE INDEX standing_orders_due_idx ON standing_orders (status, next_run_at);

COMMENT ON COLUMN standing_orders.next_run_at IS
    'When this order next pays, as an instant. Date-shaped periods are stored as start-of-day in Africa/Kigali so that "the 5th" is one moment regardless of the server time zone.';

COMMENT ON COLUMN standing_orders.last_run_at IS
    'The last occurrence actually paid. With the row id it forms the ledger idempotency key, which is what stops a repeated sweep paying twice.';

/* ----------------------------------------------------------- the test-speed frequency */

/*
 * EVERY_2_MINUTES EXISTS TO MAKE THE UNATTENDED PATH OBSERVABLE.
 *
 * Standing orders are the one way money leaves an account here without a manager releasing
 * it. That is the behaviour most worth watching before it is trusted, and waiting a month
 * to watch it once is not a test. Two minutes runs the identical code path -- same debit,
 * same credit, same idempotency key, same notifications, same advance -- at a speed a
 * person can sit through.
 *
 * It is an ordinary value, not a hidden one: it survives a restart, it shows in the
 * customer's list, and it is written in this CHECK where anybody reading the schema will
 * find it. A frequency that only existed behind a dev flag would be a different code path
 * from the one going to production, which is the opposite of a test.
 */
ALTER TABLE standing_orders DROP CONSTRAINT standing_order_frequency_known;

ALTER TABLE standing_orders ADD CONSTRAINT standing_order_frequency_known
    CHECK (frequency IN ('EVERY_2_MINUTES', 'WEEKLY', 'MONTHLY', 'QUARTERLY'));
