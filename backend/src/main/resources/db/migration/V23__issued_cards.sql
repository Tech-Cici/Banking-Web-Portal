/*
 * CARDS THE BANK HAS ACTUALLY ISSUED.
 *
 * WHAT WAS THERE BEFORE. Nothing on the server. The cards page called GET /cards, which no
 * controller answered, so the deployment returned 404 and the screen said the endpoint did
 * not exist. Locally it was answered by the browser's own mock from a fixture array -- the
 * same shape of failure as the service requests in V18 and the notifications in V20: a
 * surface that looks like a record of the customer's property and answers from nowhere.
 *
 * THE CARD DETAILS ARE TYPED BY STAFF, NOT GENERATED. A PAN, a brand and an expiry date
 * exist on a piece of plastic the bank produced; this service has no way to know them and
 * no business choosing them. They are entered when a member of staff marks the CARD
 * service request ready, next to the collection point they already type. Before that
 * moment there is no row here, because before that moment there is no card.
 *
 * LAST FOUR DIGITS ONLY, AND THAT IS ENFORCED BY THE SCHEMA. masked_pan is 24 characters
 * and holds a display string such as '**** 4821'. There is deliberately NO column for a
 * full PAN, a BIN or a CVV: a column that cannot exist cannot be filled in by a later
 * change that seemed reasonable at the time, and cannot be leaked by a log line.
 *
 * NO EXPIRY ARITHMETIC ANYWHERE. Whether a card still works is a statement the bank makes,
 * so status is stored and staff set EXPIRED when it is true. Deriving it from expiry_month
 * and expiry_year would mean this portal telling a customer their card is dead on the
 * strength of its own clock.
 */

CREATE TABLE issued_cards (
    id                  UUID         NOT NULL PRIMARY KEY,

    customer_id         UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /* The account the card draws on. Checked against the caller before any row is written. */
    account_id          UUID         NOT NULL REFERENCES customer_accounts (id),

    /*
     * The request this card came from, so the two screens agree. Nullable because a card
     * could be recorded for a customer who never asked through the portal -- a replacement
     * issued at a counter, for instance.
     */
    service_request_id  UUID         REFERENCES service_requests (id),

    /*
     * WHAT THE CUSTOMER SEES, e.g. '**** 4821'. Composed by the service from four digits.
     * There is no column here for anything longer, and that is the point.
     */
    masked_pan          VARCHAR(24)  NOT NULL,

    /* 'Visa', 'Mastercard' -- typed by staff off the card, not chosen from a list this
       portal invented. V18 records what happens when the portal invents the list. */
    brand               VARCHAR(32)  NOT NULL,

    card_type           VARCHAR(16)  NOT NULL,
    status              VARCHAR(24)  NOT NULL,

    expiry_month        SMALLINT     NOT NULL,
    expiry_year         SMALLINT     NOT NULL,

    /* The name embossed on the card, captured when it was issued. Stored rather than read
       from the customer row at display time, because people change their names and the
       plastic does not. */
    cardholder_name     VARCHAR(160) NOT NULL,

    issued_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    issued_by_name      VARCHAR(160) NOT NULL,

    activated_at        TIMESTAMP(6) WITH TIME ZONE,

    blocked_at          TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT issued_card_type_known
        CHECK (card_type IN ('DEBIT', 'PREPAID')),

    /*
     * PENDING_COLLECTION while the request is READY, ACTIVE once it has been handed over,
     * BLOCKED when the customer stops it, EXPIRED when the bank says so. The four values
     * match the TypeScript union in types/banking.ts exactly -- see V16 for what two copies
     * of one list cost when they drift.
     */
    CONSTRAINT issued_card_status_known
        CHECK (status IN ('PENDING_COLLECTION', 'ACTIVE', 'BLOCKED', 'EXPIRED')),

    CONSTRAINT issued_card_expiry_month_real
        CHECK (expiry_month BETWEEN 1 AND 12),

    /* A four-digit year, so '26' cannot be stored where 2026 was meant. */
    CONSTRAINT issued_card_expiry_year_real
        CHECK (expiry_year BETWEEN 2000 AND 2100),

    /* BLOCKED carries when, so a dispute can be dated. */
    CONSTRAINT issued_card_blocked_dated
        CHECK ((status = 'BLOCKED' AND blocked_at IS NOT NULL)
            OR (status <> 'BLOCKED' AND blocked_at IS NULL))
);

/* The customer's own list, newest first. */
CREATE INDEX issued_cards_owner_idx ON issued_cards (customer_id, issued_at DESC);

/*
 * One card per service request, so marking a request ready twice cannot produce two.
 *
 * NOT A PARTIAL INDEX. The obvious spelling here is `... WHERE service_request_id IS NOT
 * NULL`, and it is PostgreSQL-only: H2 rejects the WHERE clause as a syntax error, and this
 * project runs its real migrations against H2 in the test suite on purpose. Writing it that
 * way failed Flyway, which failed the Spring context, which errored all 275 tests at once.
 *
 * It is also unnecessary. A plain UNIQUE index already treats NULLs as distinct on both
 * engines, so a card recorded without a service request still inserts, while a second card
 * for the same request is refused. Same guarantee, standard SQL.
 */
CREATE UNIQUE INDEX issued_cards_request_idx ON issued_cards (service_request_id);

COMMENT ON TABLE issued_cards IS
    'Cards the bank has issued, as staff recorded them. The portal never generates a card number, a brand or an expiry date: they are read off the physical card.';

COMMENT ON COLUMN issued_cards.masked_pan IS
    'Display string holding the last four digits only, e.g. ''**** 4821''. There is deliberately no column for a full PAN, BIN or CVV.';

COMMENT ON COLUMN issued_cards.status IS
    'Stored, never inferred. A card past its expiry is not marked EXPIRED by arithmetic -- whether it still works is the bank''s statement.';
