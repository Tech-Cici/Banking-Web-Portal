package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A browser that has completed the emailed-code step and may sign in on the password
 * alone until it expires.
 *
 * <p>THE TOKEN IS NOT HERE. Only its hash is stored; the token itself lives in the
 * customer's cookie and in the one response that set it. See V14 for why.
 */
@Entity
@Table(name = "trusted_devices")
public class TrustedDeviceEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 200)
    private String revokedReason;

    /**
     * Browser and platform as at the moment trust was granted. May be null.
     *
     * <p>WHAT THIS IS FOR: V14's comment says {@code last_used_at} is "the column that
     * makes 'which of these is the laptop I lost' answerable". A timestamp alone does not
     * answer it — two browsers used the same afternoon are two identical rows, and the
     * customer cannot revoke the right one. Added by V19.
     *
     * <p>FROZEN AT THE MOMENT OF TRUST, not updated on later use, because what the
     * customer is identifying is the browser they granted trust from. Coarse by design:
     * see {@code DeviceDescription} on why two words and not a version string.
     *
     * <p>NULLABLE, never defaulted. A client that sends no User-Agent gets no label and
     * the screen shows the dates instead.
     */
    @Column(name = "device", length = 120)
    private String device;

    protected TrustedDeviceEntity() {
        // JPA.
    }

    private TrustedDeviceEntity(
            UUID customerId, String tokenHash, Duration trustFor, String device) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.tokenHash = tokenHash;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(trustFor);
        this.device = device == null || device.isBlank() ? null : device;
    }

    /** Records trust in a browser, for as long as the caller says. */
    public static TrustedDeviceEntity trusted(
            UUID customerId, String tokenHash, Duration trustFor, String device) {

        return new TrustedDeviceEntity(customerId, tokenHash, trustFor, device);
    }

    /**
     * Whether this device may still stand in for the emailed code.
     *
     * <p>THE CUSTOMER IS NOT CHECKED HERE, on purpose. A device is only ever usable for
     * the customer it was issued to, and comparing that is the caller's job because the
     * caller is the one that knows who is signing in — an entity that answered "am I
     * usable" without being told who was asking is an entity whose answer means nothing.
     */
    public boolean usable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** Marks this sign-in against the device, so the customer can recognise it later. */
    public void used() {
        this.lastUsedAt = Instant.now();
    }

    /**
     * Withdraws trust, with a reason.
     *
     * <p>Idempotent: revoking an already-revoked device keeps the first reason and the
     * first time, because that is when the trust actually ended.
     */
    public void revoke(String reason) {
        if (revokedAt != null) return;
        this.revokedAt = Instant.now();
        this.revokedReason = reason;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public String device() {
        return device;
    }

    /** Whether this device may still stand in for the emailed code, as of now. */
    public boolean live() {
        return usable(Instant.now());
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public String revokedReason() {
        return revokedReason;
    }
}
