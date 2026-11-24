package rw.bank.ibanking.statements;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The record that a customer took a statement.
 *
 * <p>IT HOLDS NO DOCUMENT. The bytes are re-rendered from the ledger on download; V28 says
 * why at length. The short version is that account_transactions is append-only, so the same
 * period asked for twice produces the same document, and a stored copy could only ever start
 * to disagree with the ledger it claims to describe.
 *
 * <p>NO STATUS. Generation is synchronous, so a row exists because a document was produced.
 */
@Entity
@Table(name = "statements")
public class StatementEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** The one account this covers, or null for every account the customer holds. */
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private StatementFormat format;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    protected StatementEntity() {
        // JPA.
    }

    private StatementEntity(
            UUID customerId,
            UUID accountId,
            LocalDate periodFrom,
            LocalDate periodTo,
            StatementFormat format,
            long sizeBytes) {

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.accountId = accountId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.format = format;
        this.sizeBytes = sizeBytes;
        this.requestedAt = Instant.now();
    }

    /**
     * Records a statement that has already been produced.
     *
     * <p>THE SIZE IS A PARAMETER, which is the point: there is no way to call this without
     * having rendered the document first, so the table cannot come to hold a row describing a
     * statement nobody ever made.
     */
    public static StatementEntity produced(
            UUID customerId,
            UUID accountId,
            LocalDate periodFrom,
            LocalDate periodTo,
            StatementFormat format,
            long sizeBytes) {

        return new StatementEntity(customerId, accountId, periodFrom, periodTo, format, sizeBytes);
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    /** Empty when this statement covers every account the customer holds. */
    public Optional<UUID> accountId() {
        return Optional.ofNullable(accountId);
    }

    public LocalDate periodFrom() {
        return periodFrom;
    }

    public LocalDate periodTo() {
        return periodTo;
    }

    public StatementFormat format() {
        return format;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public long sizeBytes() {
        return sizeBytes;
    }
}
