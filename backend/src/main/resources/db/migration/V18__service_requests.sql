/*
 * ASKING THE BANK FOR A PHYSICAL THING: a card, or a cheque book.
 *
 * WHAT WAS THERE BEFORE. Nothing on the server. Both screens posted to endpoints that only
 * the browser's own mock answered, and the mock pushed a row onto an in-memory array that
 * starts empty on every page load. So the request reached nobody, was visible to nobody,
 * and did not survive a refresh - the customer's reference stopped existing the moment
 * they pressed F5.
 *
 * WORSE THAN THE DEAD END: THE SCREEN MADE PROMISES. It told the customer a reference
 * number, that cards "take about five working days to print", and that they should collect
 * from a named branch bringing photo identification. The five days was a constant in a
 * React component. The branch came from a six-item array - Nyarugenge, Kimironko, Remera,
 * Musanze, Rubavu, Huye - hardcoded in the same file, and docs/OPEN-ITEMS.md already
 * recorded that the bank had never supplied a branch list. The portal was naming branches
 * it had invented and telling somebody to carry their ID to one.
 *
 * That is the failure this codebase has unpicked twice already, and the comment on
 * OutboundNotAvailablePage states the rule: an error sends somebody to a branch, a receipt
 * sends them home to wait. This sent them to a branch that may not exist.
 *
 * SO THE CUSTOMER NO LONGER CHOOSES A COLLECTION POINT - THE BANK NAMES ONE. The column
 * below is null until a member of staff fills it in when they mark the request ready,
 * because the bank is the only party that knows where the card actually ended up. Nothing
 * in the portal states a branch or a number of days until a human has said so. That also
 * means no list of branches has to be invented to ship this.
 *
 * ONE TABLE FOR BOTH KINDS, because they are one process: a customer asks for a physical
 * item, staff produce it, somebody collects it. Two tables would mean two queues, two sets
 * of statuses and two chances for them to drift.
 */

CREATE TABLE service_requests (
    id                  UUID         NOT NULL PRIMARY KEY,

    customer_id         UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /*
     * WHAT THE CUSTOMER QUOTES ON THE TELEPHONE, so it is short, unique, and avoids the
     * characters people mishear. Generated from the row's own UUID rather than from a
     * count: `nextCustomerNumber` in OnboardingService derives its value from
     * `customers.count() + 1`, which two concurrent requests can read identically and
     * which then fails on the UNIQUE index as a 500. A reference taken from the id cannot
     * collide, and the index below is the backstop for the impossible case.
     */
    reference           VARCHAR(24)  NOT NULL UNIQUE,

    /* CARD or CHEQUE_BOOK. The enum, this constraint and a TypeScript union are three
       copies of one list - see V16 for what happens when two of them disagree. */
    request_type        VARCHAR(16)  NOT NULL,

    /*
     * The account the card or cheque book belongs to. Checked against the caller before
     * the row is written, so a request can never name somebody else's account.
     */
    account_id          UUID         NOT NULL REFERENCES customer_accounts (id),

    /*
     * What was asked for, in the customer's terms: "Debit card", "Cheque book, 50
     * leaves". Composed by the service from validated input rather than accepted as free
     * text from the browser, so nothing a customer types ends up on a staff screen
     * unescaped or on a printing instruction unchecked.
     */
    details             VARCHAR(200) NOT NULL,

    /*
     * SUBMITTED until staff act. READY when it has been produced and is waiting to be
     * collected - and only then is collection_point set. COLLECTED when it has been handed
     * over, which closes the request and is the audit record that somebody received it.
     * DECLINED with a reason.
     */
    status              VARCHAR(16)  NOT NULL,

    submitted_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * WHERE TO COLLECT IT. Null until staff name it, and that is the point: the bank
     * supplies this, the portal never guesses it, and no screen states a collection point
     * that a person has not typed.
     */
    collection_point    VARCHAR(120),

    ready_at            TIMESTAMP(6) WITH TIME ZONE,
    ready_by_name       VARCHAR(160),

    collected_at        TIMESTAMP(6) WITH TIME ZONE,
    collected_by_name   VARCHAR(160),

    declined_at         TIMESTAMP(6) WITH TIME ZONE,
    declined_by_name    VARCHAR(160),
    decline_reason      VARCHAR(300),

    CONSTRAINT service_request_type_known
        CHECK (request_type IN ('CARD', 'CHEQUE_BOOK')),

    CONSTRAINT service_request_status
        CHECK (status IN ('SUBMITTED', 'READY', 'COLLECTED', 'DECLINED')),

    /*
     * READY AND BEYOND CARRY A COLLECTION POINT, WHEN AND WHO. Without this, a code path
     * that marks a request ready and forgets to name the branch leaves the customer an
     * email telling them to collect their card from nowhere.
     *
     * COLLECTED implies it was READY first, so it carries the same three.
     */
    CONSTRAINT service_request_ready_together
        CHECK ((status IN ('READY', 'COLLECTED')
                  AND collection_point IS NOT NULL
                  AND ready_at IS NOT NULL
                  AND ready_by_name IS NOT NULL)
            OR (status NOT IN ('READY', 'COLLECTED')
                  AND collection_point IS NULL
                  AND ready_at IS NULL
                  AND ready_by_name IS NULL)),

    CONSTRAINT service_request_collected_together
        CHECK ((status = 'COLLECTED' AND collected_at IS NOT NULL AND collected_by_name IS NOT NULL)
            OR (status <> 'COLLECTED' AND collected_at IS NULL AND collected_by_name IS NULL)),

    CONSTRAINT service_request_decline_reasoned
        CHECK ((status = 'DECLINED'
                  AND declined_at IS NOT NULL
                  AND declined_by_name IS NOT NULL
                  AND decline_reason IS NOT NULL)
            OR (status <> 'DECLINED'
                  AND declined_at IS NULL
                  AND declined_by_name IS NULL
                  AND decline_reason IS NULL))
);

/* The customer's own list, newest first - the request they just made is the one they are
   looking for. */
CREATE INDEX service_requests_owner_idx
    ON service_requests (customer_id, submitted_at);

/* The staff queue's "everything still open, oldest first". A queue, not a feed: newest
   first would bury whoever has been waiting for their card longest. */
CREATE INDEX service_requests_queue_idx
    ON service_requests (status, submitted_at);

COMMENT ON TABLE service_requests IS
    'Customers asking the bank for a card or a cheque book. Staff mark one ready and name where to collect it; the portal never states a branch or a lead time the bank has not given.';

COMMENT ON COLUMN service_requests.collection_point IS
    'Where to collect it, typed by the member of staff who marked it ready. Null before then - the portal has no branch list of its own and must not invent one.';

COMMENT ON COLUMN service_requests.reference IS
    'What the customer quotes on the telephone. Derived from the row id, so it cannot collide the way a count-derived number can.';

/*
 * THE TWO NOTIFICATION KINDS THIS PRODUCES.
 *
 * SAME CHANGE AS V5, V16 AND V17, FOR THE SAME REASON: OutboxKind and this CHECK
 * constraint are two copies of one list, and adding to one is half a change. Marking a
 * request ready would otherwise fail at the moment of recording the email - after the row
 * had already been updated in the same transaction, rolling the lot back as a 500.
 * OutboxKindsArePersistableTest writes one row per enum value and fails the moment the two
 * disagree.
 *
 * There is deliberately NO kind for submitting one. The customer has just pressed the
 * button; an email telling them they pressed it is noise, and the same reasoning is
 * recorded on the password-request flow.
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
                        'BENEFICIARY_REFUSED',
                        'SERVICE_REQUEST_READY',
                        'SERVICE_REQUEST_DECLINED'));
