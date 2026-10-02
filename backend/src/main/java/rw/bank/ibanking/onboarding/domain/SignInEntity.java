package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A recorded sign-in.
 *
 * <p>Exists so a customer can notice a sign-in that was not them. That is the entire
 * purpose, and it is worth stating because the first version of this class defeated it.
 *
 * <p>IT USED TO CARRY A LOCATION, and all four call sites passed the literal {@code
 * "Kigali, Rwanda"} — there is no geo-IP lookup in this service. The doc comment here said
 * the location was "deliberately coarse"; it was not coarse, it was invented, and it made
 * an intruder signing in from another country produce a row identical to the customer's
 * own. V19 drops the column. {@link SignInMethod} has the full reasoning, and
 * docs/OPEN-ITEMS.md records real geo-location as waiting on the bank.
 *
 * <p>WHAT IS HERE INSTEAD is only what the service knows for certain as it writes the row:
 * which sign-in path was taken, and — when the client sent a recognisable User-Agent — a
 * two-word description of the browser. Both may be checked against the customer's own
 * memory, which is the test any row on a security page has to pass.
 */
@Entity
@Table(name = "sign_ins")
public class SignInEntity {

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "signed_in_at", nullable = false)
    private Instant signedInAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24)
    private SignInMethod method;

    /**
     * Browser and platform, or null.
     *
     * <p>NULLABLE AND NEVER DEFAULTED. A client may legitimately send no User-Agent, and
     * an unrecognised one has to read as "we do not know" — a fallback string here is
     * exactly how the fabricated location survived as long as it did.
     */
    @Column(name = "device", length = 120)
    private String device;

    protected SignInEntity() {
        // JPA.
    }

    /**
     * Records a sign-in.
     *
     * @param method which path was taken. Required: a sign-in whose method is not known
     *     is only legitimate for rows written before V19, and those carry {@link
     *     SignInMethod#UNKNOWN} because the migration put it there rather than because
     *     anything still writes it.
     * @param device from {@code DeviceDescription}, or null when the client sent nothing
     *     recognisable. Pass null rather than a placeholder.
     */
    public SignInEntity(UUID customerId, SignInMethod method, String device) {
        if (method == null) {
            throw new IllegalArgumentException("A recorded sign-in must say how it happened.");
        }

        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.signedInAt = Instant.now();
        this.method = method;
        this.device = device == null || device.isBlank() ? null : device;
    }

    public UUID id() {
        return id;
    }

    public Instant signedInAt() {
        return signedInAt;
    }

    public SignInMethod method() {
        return method;
    }

    public String device() {
        return device;
    }
}
