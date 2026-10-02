package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One person the company says may act on its account.
 *
 * <p>NOT VERIFIED, AND NOT YET AUTHORISED TO DO ANYTHING. A row here is a name, a role
 * and a national ID number that somebody typed into a public form. Nothing in this
 * service checks any of it, nobody named here gains access by being named, and a
 * signatory becomes able to act only through a separate, deliberate step the bank has not
 * yet specified.
 *
 * <p>That matters more here than on the company columns: a signatory list is a list of
 * people who could eventually move the company's money. Treating it as authoritative
 * because it arrived in a structured table would be the most expensive mistake available
 * in this flow.
 *
 * <p>{@link #ordinal()} preserves the order the applicant listed them in. The first is
 * conventionally the primary signatory, and that is information only the applicant has —
 * re-sorting the list by name would throw it away.
 */
@Entity
@Table(name = "business_signatories")
public class BusinessSignatoryEntity {

    @Id private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(nullable = false)
    private short ordinal;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(nullable = false, length = 120)
    private String role;

    /**
     * As typed. Not validated for shape here, and never treated as identification.
     *
     * <p>The personal registration form requires 16 digits; this one does not, because a
     * signatory may be a foreign director whose identity document is not a Rwandan
     * national ID. Refusing those at a public form would turn away a real company, and
     * the check that matters is the one a human does against a document.
     */
    @Column(name = "national_id", nullable = false, length = 40)
    private String nationalId;

    @Column(length = 254)
    private String email;

    @Column(length = 32)
    private String phone;

    protected BusinessSignatoryEntity() {
        // JPA.
    }

    private BusinessSignatoryEntity(
            UUID applicationId,
            int ordinal,
            String fullName,
            String role,
            String nationalId,
            String email,
            String phone) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.ordinal = (short) ordinal;
        this.fullName = fullName;
        this.role = role;
        this.nationalId = nationalId;
        this.email = blankToNull(email);
        this.phone = blankToNull(phone);
    }

    public static BusinessSignatoryEntity listed(
            UUID applicationId,
            int ordinal,
            String fullName,
            String role,
            String nationalId,
            String email,
            String phone) {
        return new BusinessSignatoryEntity(
                applicationId, ordinal, fullName, role, nationalId, email, phone);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() {
        return id;
    }

    public UUID applicationId() {
        return applicationId;
    }

    public short ordinal() {
        return ordinal;
    }

    public String fullName() {
        return fullName;
    }

    public String role() {
        return role;
    }

    public String nationalId() {
        return nationalId;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }
}
