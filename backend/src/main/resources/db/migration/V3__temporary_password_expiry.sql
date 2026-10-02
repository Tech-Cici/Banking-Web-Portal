-- When the temporary password stops working.
--
-- Added because the temporary password is now EMAILED to the customer rather than handed
-- over in person. That was a deliberate decision, and this column is what stops it being
-- an unbounded one.
--
-- A password sent by email lives in a mailbox indefinitely. Without an expiry, a mailbox
-- breached a year later still yields a working credential for a bank account that its
-- owner may never have signed into. With one, the window is a few days: after that the
-- emailed password is worthless and the account has to be reissued by staff, which is a
-- conversation with a human rather than a silent takeover.
--
-- Nullable, because it is only meaningful while must_change_password is true. Once the
-- customer chooses their own password there is nothing to expire, and a stale timestamp
-- left behind would be a trap for whoever next reads this table.

ALTER TABLE customers
    ADD COLUMN temporary_password_expires_at TIMESTAMP(6) WITH TIME ZONE;

-- Existing rows predate the emailed-password flow: their credential was handed over in
-- person and no expiry was promised, so leaving it NULL is the honest value. The sign-in
-- check treats NULL as "does not expire" for exactly that reason.
COMMENT ON COLUMN customers.temporary_password_expires_at IS
    'When an emailed temporary password stops working. NULL means no expiry applies '
    '(the password was handed over in person, or has already been replaced).';
