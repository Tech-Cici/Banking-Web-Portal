/*
 * IN-APP NOTIFICATIONS, WHICH HAD NO TABLE AND NO WRITER.
 *
 * The portal shipped a notifications panel on the dashboard, a full notifications page, a
 * mark-as-read action and a mark-all-read action. Nothing on the server had ever heard of
 * any of it: `/notifications` was answered by MSW from an array that starts empty on every
 * page load, so the panel read "Nothing new." permanently, whatever the bank had just done
 * to the account.
 *
 * HOW IT WAS FOUND. A customer asked for a card, a member of staff marked it ready and
 * named a collection point, the customer was emailed - and the dashboard still said
 * "Nothing new." The email worked; the in-app surface was structurally empty.
 *
 * THAT IS WORSE THAN AN ABSENT PANEL. A notification list is read as a record: a customer
 * who sees "Nothing new." concludes that nothing happened. The panel was making a negative
 * claim it had no means of checking.
 *
 * WRITTEN WHERE THE EMAIL IS SENT, not derived from the outbox. The outbox is the bank's
 * record of messages it sent, keyed by email address, and it carries staff-facing traffic
 * too; a customer's notification list is a different thing with a different audience. Each
 * service writes both, in the same transaction, so a notification cannot exist for an event
 * that was rolled back and an event cannot complete having told the customer only half.
 *
 * WHAT DELIBERATELY DOES NOT APPEAR HERE. Account created, approved, rejected, frozen, and
 * a re-issued or refused password. Every one of those happens at a moment when the customer
 * CANNOT SIGN IN - that is usually the point of it - so an in-app notification would be
 * unreadable by construction. Email is the only channel that reaches them, which is why
 * they are emails. Adding rows nobody can read would make this table look more complete
 * than the product is.
 */

CREATE TABLE notifications (
    id           UUID         NOT NULL PRIMARY KEY,

    customer_id  UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /*
     * The enum, this constraint and the TypeScript union are three copies of one list.
     * NotificationKindsArePersistableTest is what stops them drifting - V5 is what happened
     * last time they did: ACCOUNT_FROZEN went into OutboxKind and not into its constraint,
     * so freezing an account failed writing its notification inside the same transaction,
     * rolled the suspension back, and returned 500 for a feature that appeared merely
     * broken. That failure mode is exactly what this table reintroduces if the list drifts,
     * because these writes sit inside the transaction that did the thing.
     */
    kind         VARCHAR(32)  NOT NULL,

    title        VARCHAR(120) NOT NULL,
    body         VARCHAR(500) NOT NULL,

    /*
     * AN IN-APP PATH, OR NOTHING. Never an absolute URL, and the constraint says so rather
     * than trusting every future call site.
     *
     * A notification is attacker-influenced content in any real bank - it carries a staff
     * member's typed reason, a payee name the customer chose, a collection point - and a
     * rendered external href in a message on the bank's own domain is a phishing vector
     * with the bank's credibility behind it. The panel already refuses to link anything not
     * starting with "/"; this is the same rule one layer down, where it cannot be forgotten.
     */
    link         VARCHAR(200),

    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * WHEN IT WAS READ, not a boolean. "Read" is a fact with a time, and the time is what
     * answers "had the customer seen this before they rang us" - which is the question
     * actually asked when somebody disputes a transfer or a collection.
     */
    read_at      TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT notifications_kind_known
        CHECK (kind IN ('SERVICE_REQUEST_READY',
                        'SERVICE_REQUEST_DECLINED',
                        'BENEFICIARY_APPROVED',
                        'BENEFICIARY_REFUSED',
                        'TRANSFER_RELEASED',
                        'TRANSFER_REJECTED',
                        'PASSWORD_CHANGED',
                        'ACCOUNT_UNFROZEN')),

    CONSTRAINT notifications_link_is_internal
        CHECK (link IS NULL OR link LIKE '/%'),

    /* A row read before it existed is a clock problem, and silently wrong either way. */
    CONSTRAINT notifications_read_after_created
        CHECK (read_at IS NULL OR read_at >= created_at)
);

/*
 * THE ONLY QUERY THIS TABLE SERVES is "this customer's notifications, newest first", and
 * the panel runs it on every dashboard load. Ordered DESC in the index so the common read
 * needs no sort.
 */
CREATE INDEX notifications_customer_idx ON notifications (customer_id, created_at DESC);

COMMENT ON TABLE notifications IS
    'A customer''s in-app notifications. Written in the same transaction as the event that caused them, alongside the email. Only events a signed-in customer can actually read are recorded here.';

COMMENT ON COLUMN notifications.link IS
    'In-app path only, enforced by notifications_link_is_internal. A notification carries attacker-influenced text, so an external href here would be phishing on the bank''s own domain.';

COMMENT ON COLUMN notifications.read_at IS
    'When the customer read it. A timestamp rather than a flag, because "had they seen this yet" is the question asked when something is disputed.';
