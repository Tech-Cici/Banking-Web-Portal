/*
 * COLLATERAL PLEDGED AGAINST A LOAN, AND THE DOCUMENT THAT EVIDENCES IT.
 *
 *
 * 1. WHY THE DOCUMENT IS KEPT AT ALL.
 *
 * A customer pledges a plot, a house, a vehicle or a business and types its identifier. The
 * typed identifier is a claim. The document is the evidence for it, and a member of staff
 * deciding whether to lend against a plot has to be able to look at the title rather than at
 * a transcription of it. So the bytes are kept, not only the text read out of them.
 *
 * IN THE DATABASE, NOT ON A DISK. A path in a row and a file beside it are two things that
 * drift: a restored backup has rows whose files are missing, a failed transaction leaves a
 * file nobody references, and deleting a customer leaves their title deed on a volume. Here
 * the document commits and rolls back with the row that owns it, and ON DELETE CASCADE means
 * removing a customer actually removes their documents. The price is that a large upload sits
 * in the database; the cap below is what keeps that honest.
 *
 *
 * 2. WHAT THE OCR IS, AND WHAT IT IS NOT.
 *
 * ocr_text IS WHAT A MACHINE THOUGHT THE PAGE SAID. It is not a fact about the collateral, it
 * is a reading, and readings of a photographed document are wrong often enough that the whole
 * design here follows from assuming so: the customer sees what was read and corrects it, the
 * declared value is what they submitted, and no decision is taken from the reading alone.
 *
 * THE STATUS IS SEPARATE FROM THE TEXT. "Nothing legible on the page" and "the reader fell
 * over" are different outcomes and lead to different sentences on screen; an empty text column
 * cannot tell them apart.
 *
 *
 * 3. FIELDS ARE ROWS, NOT COLUMNS.
 *
 * Each kind of collateral is evidenced by a different document with different identifiers on
 * it -- a parcel has a UPI, a vehicle has a plate and a chassis number, a company has its
 * registration code. Columns named identifier_one and identifier_two, meaning something
 * different per kind, are columns every future reader has to decode and every query has to
 * special-case. One row per field, keyed by name, says what it holds.
 *
 * EACH FIELD CARRIES BOTH WHAT WAS DECLARED AND WHAT WAS READ, and whether they agreed. That
 * comparison is made once, when the application is submitted, and recorded -- because it is a
 * statement about the document as it was read that day, and re-deriving it later from a
 * reading that has since been redone would quietly change the history of a credit decision.
 *
 *
 * 4. WHAT THIS DELIBERATELY DOES NOT MODEL.
 *
 * NO VALUATION. What a plot is worth is not written on its title, it is a surveyor's opinion,
 * and a number typed by the applicant beside the word "value" would be read by a manager as
 * though the bank had established it. Valuation belongs with underwriting and is not here.
 *
 * NO VERIFICATION AGAINST A REGISTRY. Nothing in this portal can ask the land authority
 * whether a UPI exists or who owns it. A field that agrees with the document only means the
 * customer typed what the document says; the document itself is unverified.
 */

/* ------------------------------------------------------------------ the document */

CREATE TABLE collateral_documents (
    id                UUID         NOT NULL PRIMARY KEY,

    customer_id       UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /* What the customer says this document evidences. The reader uses it to choose patterns. */
    kind              VARCHAR(16)  NOT NULL,

    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        INTEGER      NOT NULL,

    /*
     * THE HASH OF THE BYTES. Two purposes: an upload repeated after a dropped connection is
     * recognisable as the same document rather than stored twice, and a member of staff can
     * say that the file they are looking at is byte-for-byte the one that was uploaded.
     */
    sha256            CHAR(64)     NOT NULL,

    content           BYTEA        NOT NULL,

    uploaded_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /*
     * LINKED ONLY WHEN THE APPLICATION IS SUBMITTED. A customer who uploads a title and then
     * abandons the form has left a scan of their property in the bank's database attached to
     * nothing. That is exactly the sensitive data that should not be kept, so it is dated
     * here and swept.
     */
    linked_at         TIMESTAMP(6) WITH TIME ZONE,

    /* ---------------------------------------------------------------- the reading */

    ocr_status        VARCHAR(16)  NOT NULL,
    ocr_text          TEXT,
    ocr_engine        VARCHAR(64),
    ocr_at            TIMESTAMP(6) WITH TIME ZONE,
    ocr_failure       VARCHAR(400),

    /* An empty file is not a document, and 8 MB is more than any phone photograph of a page. */
    CONSTRAINT collateral_document_size_sane
        CHECK (size_bytes > 0 AND size_bytes <= 8388608),

    CONSTRAINT collateral_document_kind_known
        CHECK (kind IN ('LAND', 'HOUSE', 'VEHICLE', 'BUSINESS')),

    CONSTRAINT collateral_document_ocr_status_known
        CHECK (ocr_status IN ('READ', 'UNREADABLE', 'FAILED')),

    /* A reading that succeeded has text and a time; one that did not has neither. */
    CONSTRAINT collateral_document_read_together
        CHECK ((ocr_status = 'READ' AND ocr_text IS NOT NULL AND ocr_at IS NOT NULL)
            OR (ocr_status <> 'READ' AND ocr_text IS NULL)),

    /* A failure that cannot say what failed is a failure nobody can act on. */
    CONSTRAINT collateral_document_failure_explained
        CHECK ((ocr_status = 'FAILED' AND ocr_failure IS NOT NULL)
            OR (ocr_status <> 'FAILED' AND ocr_failure IS NULL))
);

CREATE INDEX collateral_documents_owner_idx
    ON collateral_documents (customer_id, uploaded_at DESC);

/*
 * The sweep of abandoned uploads reads this: linked_at first, because it is the column that
 * selects the rows, then the date that orders them.
 *
 * NOT A PARTIAL INDEX, although PostgreSQL would take one and it would be smaller. Every
 * migration in this project is replayed against H2 before it is placed, H2 has no partial
 * indexes, and an index that cannot be rehearsed is worth less than the bytes it saves.
 */
CREATE INDEX collateral_documents_unlinked_idx
    ON collateral_documents (linked_at, uploaded_at);

COMMENT ON COLUMN collateral_documents.ocr_text IS
    'What the reader thought the page said. A reading, not a fact: nothing is decided from it, and the customer corrects it before anything is submitted.';

COMMENT ON COLUMN collateral_documents.linked_at IS
    'When this document became part of a submitted application. Null means it is still an abandoned upload, and abandoned uploads of title deeds are swept.';

/* --------------------------------------------------------------- the pledge */

CREATE TABLE loan_collateral (
    id                 UUID        NOT NULL PRIMARY KEY,

    customer_id        UUID        NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    /* The loan application this is pledged against. */
    service_request_id UUID        NOT NULL REFERENCES service_requests (id) ON DELETE CASCADE,

    document_id        UUID        NOT NULL REFERENCES collateral_documents (id),

    kind               VARCHAR(16) NOT NULL,

    /* The customer's own words for the thing: "Plot at Kabeza", "Toyota Hiace". */
    description        VARCHAR(160) NOT NULL,

    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT loan_collateral_kind_known
        CHECK (kind IN ('LAND', 'HOUSE', 'VEHICLE', 'BUSINESS')),

    /* One document evidences one pledge. Two pledges on one scan is a mistake, not a case. */
    CONSTRAINT loan_collateral_document_once
        UNIQUE (document_id)
);

CREATE INDEX loan_collateral_application_idx
    ON loan_collateral (service_request_id);

/* ---------------------------------------------------- what was declared, what was read */

CREATE TABLE collateral_fields (
    id             UUID         NOT NULL PRIMARY KEY,

    collateral_id  UUID         NOT NULL REFERENCES loan_collateral (id) ON DELETE CASCADE,

    /* UPI, PLATE, CHASSIS, COMPANY_CODE, OWNER_NAME -- which ones apply depends on the kind. */
    field_key      VARCHAR(40)  NOT NULL,

    /* What the customer submitted, after correcting whatever was read. Always present. */
    declared_value VARCHAR(200) NOT NULL,

    /* What the reader found, if it found anything. Null is "we could not read this field". */
    read_value     VARCHAR(200),

    agreement      VARCHAR(16)  NOT NULL,

    CONSTRAINT collateral_field_agreement_known
        CHECK (agreement IN ('SAME', 'DIFFERENT', 'NOT_READ')),

    /*
     * NOT_READ AND A VALUE CANNOT BOTH BE TRUE, in either direction. The verdict and the
     * reading are two halves of one sentence, and a row saying "we could not read it" beside
     * the thing it read would be a row a reviewer cannot act on.
     */
    CONSTRAINT collateral_field_verdict_matches_reading
        CHECK ((agreement = 'NOT_READ' AND read_value IS NULL)
            OR (agreement <> 'NOT_READ' AND read_value IS NOT NULL)),

    CONSTRAINT collateral_field_once_per_collateral
        UNIQUE (collateral_id, field_key)
);

COMMENT ON COLUMN collateral_fields.agreement IS
    'Whether the declared value matched what was read, compared once at submission and kept. DIFFERENT is not a refusal: a customer correcting a misread is the system working.';
