package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A one-time code issued during sign-in. See V2__login_challenges.sql. */
@Entity
@Table(name = "login_challenges")
public class LoginChallengeEntity {

    /** Wrong answers before the code dies. Lower than registration: this guards an account. */
    public static final short MAX_ATTEMPTS = 3;

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected LoginChallengeEntity() {
        // JPA.
    }

    private LoginChallengeEntity(UUID customerId, String codeHash, Duration validity) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.codeHash = codeHash;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(validity);
        this.attempts = 0;
    }

    public static LoginChallengeEntity issue(UUID customerId, String codeHash, Duration validity) {
        return new LoginChallengeEntity(customerId, codeHash, validity);
    }

    public boolean usable(Instant now) {
        return consumedAt == null && attempts < MAX_ATTEMPTS && !now.isAfter(expiresAt);
    }

    public void recordFailedAttempt() {
        this.attempts = (short) (this.attempts + 1);
    }

    public void consume() {
        this.consumedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public String codeHash() {
        return codeHash;
    }

    public short attempts() {
        return attempts;
    }
}
