package rw.bank.ibanking.collateral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One thing pledged against one loan application, and the document that evidences it.
 *
 * <p>IT HOLDS NO VALUE AND NO VERDICT. What the plot is worth is a surveyor's opinion and is
 * not on the title; whether the bank accepts the pledge is the manager's decision and lives in
 * the application's own status. This row says only: this customer offered this thing, with
 * this document, on this application.
 */
@Entity
@Table(name = "loan_collateral")
public class LoanCollateralEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "service_request_id", nullable = false)
    private UUID serviceRequestId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CollateralKind kind;

    @Column(nullable = false, length = 160)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanCollateralEntity() {
        // JPA.
    }

    private LoanCollateralEntity(
            UUID customerId,
            UUID serviceRequestId,
            UUID documentId,
            CollateralKind kind,
            String description) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.serviceRequestId = serviceRequestId;
        this.documentId = documentId;
        this.kind = kind;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public static LoanCollateralEntity pledged(
            UUID customerId,
            UUID serviceRequestId,
            UUID documentId,
            CollateralKind kind,
            String description) {

        return new LoanCollateralEntity(
                customerId, serviceRequestId, documentId, kind, description);
    }

    public UUID id() {
        return id;
    }

    public UUID serviceRequestId() {
        return serviceRequestId;
    }

    public UUID documentId() {
        return documentId;
    }

    public CollateralKind kind() {
        return kind;
    }

    public String description() {
        return description;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
