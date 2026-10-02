package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * A customer has asked the bank for a new password.
 *
 * <p>The row is the whole of the request: there is no token, no link and no secret in it,
 * because this flow issues nothing on its own. A manager acting on it re-issues a
 * temporary password through the same path used at approval. See V15 for why it is built
 * that way rather than as a self-service reset.
 *
 * <p>A row only ever exists for a REAL customer. The endpoint answers identically whether
 * the address typed belongs to one or not, and writes nothing when it does not — a table
 * of addresses people guessed is a list of addresses that do not bank here.
 */
@Entity
@Table(name = "password_reset_requests")
public class PasswordResetRequestEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PasswordResetStatus status;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "settled_by_name", length = 160)
    private String settledByName;

    @Column(name = "refused_reason", length = 300)
    private String refusedReason;

    protected PasswordResetRequestEntity() {
        // JPA.
    }

    private PasswordResetRequestEntity(UUID customerId) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.requestedAt = Instant.now();
        this.status = PasswordResetStatus.PENDING;
    }

    /** Records that this customer has asked for a new password. */
    public static PasswordResetRequestEntity raisedBy(UUID customerId) {
        return new PasswordResetRequestEntity(customerId);
    }

    /**
     * Marks the request done, after a temporary password has been issued.
     *
     * <p>THE GUARD IS THE POINT. Two managers opening the queue at once would otherwise
     * both issue a password, and the second would silently invalidate the first — so the
     * customer reads two emails, and the one they try first does not work. Refusing the
     * second settlement makes that a visible conflict instead.
     */
    public void fulfilled(String managerName) {
        requirePending(managerName);
        this.status = PasswordResetStatus.FULFILLED;
        this.settledAt = Instant.now();
        this.settledByName = managerName;
    }

    /** Marks the request refused, with the reason the customer will be told. */
    public void refused(String managerName, String reason) {
        requirePending(managerName);
        if (reason == null || reason.isBlank()) {
            /*
             * Mandatory, and the database says so too. A refusal with no reason is one
             * nobody can explain to the customer who rings back, and "the bank said no" is
             * the answer that guarantees a second call.
             */
            throw new BusinessRuleException(
                    "Give a reason for refusing this request, so whoever speaks to the customer"
                            + " next can explain it.");
        }
        this.status = PasswordResetStatus.REFUSED;
        this.settledAt = Instant.now();
        this.settledByName = managerName;
        this.refusedReason = reason.trim();
    }

    private void requirePending(String managerName) {
        if (managerName == null || managerName.isBlank()) {
            throw new IllegalArgumentException("A settled request must name the manager.");
        }
        if (status != PasswordResetStatus.PENDING) {
            throw new BusinessRuleException(
                    "Another manager has already dealt with this request. Refresh the page to see"
                            + " what happened.");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public PasswordResetStatus status() {
        return status;
    }

    public Instant settledAt() {
        return settledAt;
    }

    public String settledByName() {
        return settledByName;
    }

    public String refusedReason() {
        return refusedReason;
    }
}
