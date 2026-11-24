package rw.bank.ibanking.collateral;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A document a customer uploaded as evidence of collateral, and what was read off it.
 *
 * <p>THE BYTES ARE LAZY, and that matters more here than anywhere else in this codebase. Every
 * other entity is a few hundred bytes; this one carries a photograph. A staff queue listing
 * twenty applications would otherwise pull twenty megabytes of JPEG out of the database to
 * show twenty file names.
 *
 * <p>WHAT IT DOES NOT HOLD: any judgement. There is no "verified" flag, because nothing in
 * this portal can verify a title against the land registry, and a column called verified would
 * be read by the next person as though something had.
 */
@Entity
@Table(name = "collateral_documents")
public class CollateralDocumentEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CollateralKind kind;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    /*
     * NO @Lob, AND THAT IS NOT AN OVERSIGHT. On PostgreSQL, Hibernate 6 maps an @Lob byte[]
     * to a large object -- an oid column holding a handle into pg_largeobject -- not to the
     * BYTEA column V35 actually creates. The two look identical in Java and disagree in the
     * database, which surfaces as a failure to write the first document rather than as
     * anything about annotations. A plain byte[] is what BYTEA wants.
     *
     * LAZY IS A HINT, NOT A GUARANTEE. Hibernate honours it on a basic field only with
     * bytecode instrumentation, which this build does not run. It stays because it says what
     * is intended, and because every screen that lists documents must be written as though
     * fetching one costs a megabyte -- which today it does.
     */
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_status", nullable = false, length = 16)
    private OcrStatus ocrStatus;

    @Column(name = "ocr_text")
    private String ocrText;

    @Column(name = "ocr_engine", length = 64)
    private String ocrEngine;

    @Column(name = "ocr_at")
    private Instant ocrAt;

    @Column(name = "ocr_failure", length = 400)
    private String ocrFailure;

    protected CollateralDocumentEntity() {
        // JPA.
    }

    private CollateralDocumentEntity(
            UUID customerId,
            CollateralKind kind,
            String originalFilename,
            String contentType,
            byte[] content,
            String sha256,
            DocumentReading reading) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.kind = kind;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content;
        this.sizeBytes = content.length;
        this.sha256 = sha256;
        this.uploadedAt = Instant.now();

        this.ocrStatus = reading.status();
        this.ocrText = reading.text();
        this.ocrEngine = reading.engine();
        this.ocrFailure = reading.failure();
        this.ocrAt = reading.status() == OcrStatus.READ ? Instant.now() : null;
    }

    public static CollateralDocumentEntity uploaded(
            UUID customerId,
            CollateralKind kind,
            String originalFilename,
            String contentType,
            byte[] content,
            String sha256,
            DocumentReading reading) {

        return new CollateralDocumentEntity(
                customerId, kind, originalFilename, contentType, content, sha256, reading);
    }

    /**
     * Marks the document as part of a submitted application, which stops it being swept.
     *
     * <p>ONCE. A document already attached to one application is not re-dated by being looked
     * at again; the date is when it stopped being an abandoned upload.
     */
    public void linked() {
        if (linkedAt == null) {
            this.linkedAt = Instant.now();
        }
    }

    public boolean isLinked() {
        return linkedAt != null;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public CollateralKind kind() {
        return kind;
    }

    public String originalFilename() {
        return originalFilename;
    }

    public String contentType() {
        return contentType;
    }

    public int sizeBytes() {
        return sizeBytes;
    }

    public String sha256() {
        return sha256;
    }

    public byte[] content() {
        return content;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    public OcrStatus ocrStatus() {
        return ocrStatus;
    }

    public String ocrText() {
        return ocrText;
    }

    public String ocrEngine() {
        return ocrEngine;
    }

    public String ocrFailure() {
        return ocrFailure;
    }
}
