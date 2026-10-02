package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * A company that banks here, created when a manager-approved application is turned into a
 * login.
 *
 * <p>DISTINCT FROM THE APPLICATION IT CAME FROM. The application is what somebody claimed;
 * this is what the bank accepted, on the date it accepted it. The details are copied rather
 * than read back through the application id, because an application can later be corrected
 * and that must not silently restate what the company was admitted as.
 *
 * <p>IT HOLDS THE ACCOUNTS. A company account is a {@code customer_accounts} row whose
 * {@code company_id} is this company; access to it runs through
 * {@link CorporateMembershipEntity} rather than through whoever the account was entered
 * against. That is the whole point of this class existing: hang a company's accounts off a
 * person and removing that person orphans the money, while a second signatory needs their
 * own copy of every account — two records of one balance.
 */
@Entity
@Table(name = "companies")
public class CompanyEntity {

    /**
     * How the join code is generated.
     *
     * <p>Random, not derived from the company name. A code somebody can guess from
     * "Inyange Industries" is a way into a company's banking, and the whole purpose of a
     * code is that only people the administrator gave it to have it.
     *
     * <p>No I, O, 1 or 0: the code gets read down a phone and written on paper.
     */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int CODE_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id private UUID id;

    @Column(name = "application_id", nullable = false, unique = true)
    private UUID applicationId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Shared by an administrator with colleagues who want access. Never guessable. */
    @Column(nullable = false, unique = true, length = 24)
    private String code;

    @Column(name = "registration_number", nullable = false, length = 60)
    private String registrationNumber;

    @Column(nullable = false, length = 40)
    private String tin;

    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyEntity() {
        // JPA.
    }

    private CompanyEntity(
            UUID applicationId,
            String name,
            String registrationNumber,
            String tin,
            String createdBy) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.name = name;
        this.code = newCode();
        this.registrationNumber = registrationNumber;
        this.tin = tin;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * Admits a company, from the application the bank accepted.
     *
     * @param createdBy the administrator's name. The record of who admitted this company,
     *     which is the first thing anybody would want if it turns out to have been
     *     admitted on the wrong paperwork.
     */
    public static CompanyEntity admitted(
            UUID applicationId,
            String name,
            String registrationNumber,
            String tin,
            String createdBy) {
        return new CompanyEntity(applicationId, name, registrationNumber, tin, createdBy);
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    public UUID id() {
        return id;
    }

    public UUID applicationId() {
        return applicationId;
    }

    public String name() {
        return name;
    }

    public String code() {
        return code;
    }

    public String registrationNumber() {
        return registrationNumber;
    }

    public String tin() {
        return tin;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
