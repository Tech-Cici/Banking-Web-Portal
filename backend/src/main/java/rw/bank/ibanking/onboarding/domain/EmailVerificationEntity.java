package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A verification code in flight, and the registration details it is guarding.
 *
 * <p>Two properties matter more than anything else here.
 *
 * <p><b>The code is stored hashed.</b> A six-digit code is a credential for as long as it
 * lives. Stored in clear, a database dump or an over-broad SELECT hands out a working code
 * for every registration currently in flight.
 *
 * <p><b>Attempts are counted.</b> A million combinations sounds like a lot and falls in
 * minutes to an unlimited guesser, so the code dies after a handful of wrong answers
 * rather than after its expiry.
 *
 * <p>The submitted details live here rather than being re-sent by the client at the final
 * step. If the client sent them again, an applicant could verify a code sent to their own
 * address and then submit somebody else's national id.
 */
@Entity
@Table(name = "email_verifications")
public class EmailVerificationEntity {

    /** Wrong answers allowed before the code is dead. */
    public static final short MAX_ATTEMPTS = 5;

    @Id private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * WHICH REGISTRATION THE PAYLOAD BELONGS TO.
     *
     * <p>Stored, not inferred. {@link #payload} is JSON, and with one flow the service
     * could simply read it back as personal details. Two flows share this table, so a
     * resend without a discriminator would deserialise a company into a person — failing
     * in a way that reads as a corrupt database rather than as a missing field.
     *
     * <p>Only PERSONAL and BUSINESS reach here. JOIN_BUSINESS is a different thing: it
     * asks an existing company's administrator for access rather than proving an email
     * address, so it has no code to guard.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationKind kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(unique = true, length = 64)
    private String token;

    protected EmailVerificationEntity() {
        // JPA.
    }

    private EmailVerificationEntity(
            String email,
            String codeHash,
            ApplicationKind kind,
            String payload,
            Duration validity) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.codeHash = codeHash;
        this.kind = kind;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(validity);
        this.attempts = 0;
    }

    /**
     * @param kind which registration flow this code is guarding. Required rather than
     *     defaulted to PERSONAL: a new flow that forgot to pass it would have its
     *     payload read back as a person, and the failure would surface at resend — long
     *     after the code that caused it.
     */
    public static EmailVerificationEntity issue(
            String email,
            String codeHash,
            ApplicationKind kind,
            String payload,
            Duration validity) {
        return new EmailVerificationEntity(email, codeHash, kind, payload, validity);
    }

    public boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean consumed() {
        return consumedAt != null;
    }

    public boolean exhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    /** Whether this code is still worth checking at all. */
    public boolean usable(Instant now) {
        return !consumed() && !exhausted() && !expired(now);
    }

    public void recordFailedAttempt() {
        this.attempts = (short) (this.attempts + 1);
    }

    /** Marks the code used and returns the single-use token that replaces it. */
    public void consume(String issuedToken) {
        this.consumedAt = Instant.now();
        this.token = issuedToken;
    }

    /**
     * Burns the token after the registration is completed.
     *
     * <p>Cleared rather than left in place: the token is the authority to submit these
     * details, and a reusable one would let the same verification create any number of
     * applications.
     */
    public void burnToken() {
        this.token = null;
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String codeHash() {
        return codeHash;
    }

    public ApplicationKind kind() {
        return kind;
    }

    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public short attempts() {
        return attempts;
    }

    public String token() {
        return token;
    }
}
