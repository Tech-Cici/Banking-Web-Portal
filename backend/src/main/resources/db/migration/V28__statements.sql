/*
 * STATEMENTS A CUSTOMER HAS TAKEN.
 *
 * WHAT THIS TABLE IS NOT: a queue. There is no status column, because generating a statement
 * here is synchronous -- the customer presses the button and the file comes back. A row
 * exists because a document was produced, so a REQUESTED or GENERATING state would be a
 * state nothing can ever be in, and a READY that is always READY is a column that says
 * nothing. The portal does not manufacture lifecycle it does not have.
 *
 * WHAT IT IS NOT, SECOND: a file store. The bytes are not kept. A statement is a view of the
 * ledger over a date range, account_transactions is append-only, and so the same range asked
 * for twice renders the same document. Storing the bytes would add a blob column, a size
 * limit and a backup question, in exchange for the ability to serve a file that could quietly
 * disagree with the ledger it claims to describe.
 *
 * WHAT IT IS: the record that a customer asked, what they asked for, and when -- which is
 * what lets the list offer the same statement again without them re-entering the dates, and
 * what an auditor would want to see if a customer ever says they were shown something else.
 *
 * account_id IS NULLABLE ON PURPOSE. Null means every account the customer holds, which is a
 * statement this bank's customers actually want: one customer number carries one account per
 * product, so "my transactions" is usually a question about all of them at once.
 */

CREATE TABLE statements (
    id              UUID NOT NULL PRIMARY KEY,

    customer_id     UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /* One account, or NULL for every account the customer holds. */
    account_id      UUID REFERENCES customer_accounts (id) ON DELETE CASCADE,

    /* Dates, not instants: a statement covers days, and both ends are inclusive. */
    period_from     DATE NOT NULL,
    period_to       DATE NOT NULL,

    format          VARCHAR(8) NOT NULL,

    requested_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * The size of the document that was actually produced. Recorded rather than recomputed
     * so the list can show it without rendering every statement it lists.
     */
    size_bytes      BIGINT NOT NULL,

    CONSTRAINT statement_format_known
        CHECK (format IN ('PDF', 'CSV')),

    /* A period that ends before it starts is a request nothing can answer. */
    CONSTRAINT statement_period_ordered
        CHECK (period_to >= period_from),

    /* A zero-byte document is a failure that was recorded as a success. */
    CONSTRAINT statement_size_positive
        CHECK (size_bytes > 0)
);

/* The customer's own list, newest first. */
CREATE INDEX statements_owner_idx ON statements (customer_id, requested_at DESC);

COMMENT ON TABLE statements IS
    'Statements a customer has taken. Metadata only: the document is re-rendered from the append-only ledger on download rather than stored.';

COMMENT ON COLUMN statements.account_id IS
    'The single account this covers, or NULL for every account the customer holds.';
