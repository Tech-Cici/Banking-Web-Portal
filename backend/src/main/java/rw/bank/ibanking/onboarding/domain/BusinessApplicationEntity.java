package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The company details on a business registration.
 *
 * <p>Alongside {@link ApplicationEntity} rather than inside it, keyed by the same id. The
 * parent row holds what every registration has — reference, status, who to contact,
 * whether that address was proven; this holds what only a company has. Folding these
 * columns into the parent would make every personal registration carry a TIN it can never
 * use.
 *
 * <p>EVERYTHING HERE IS A CLAIM. This service has no company register to check a
 * registration number against, no tax authority to confirm a TIN with, and no way to know
 * whether the person who filled the form may act for the company. An administrator reads
 * these against the bank's own records, and the approving manager is where a human takes
 * responsibility for having done so. No screen may present any of it as verified before
 * then.
 *
 * <p>THE CONTACT'S EMAIL IS NOT HERE. It is on the parent row, because that is the
 * address the verification code went to and the address the approval will go to — the one
 * the bank actually corresponds with. {@link #companyEmail} is the company's own
 * switchboard inbox, which is a different thing and frequently a shared one.
 */
@Entity
@Table(name = "business_applications")
public class BusinessApplicationEntity {

    /** Shares the application's id: one business application per application, or none. */
    @Id
    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "registration_number", nullable = false, length = 60)
    private String registrationNumber;

    /**
     * The tax identification number. NOT unique, deliberately — see the migration.
     *
     * <p>A unique constraint would refuse a duplicate on a public, unauthenticated form,
     * and that refusal tells anyone who asks which companies bank here.
     */
    @Column(nullable = false, length = 40)
    private String tin;

    @Column(name = "business_type", nullable = false, length = 80)
    private String businessType;

    @Column(nullable = false, length = 80)
    private String sector;

    @Column(nullable = false, length = 400)
    private String address;

    @Column(name = "company_email", nullable = false, length = 254)
    private String companyEmail;

    @Column(name = "company_phone", nullable = false, length = 32)
    private String companyPhone;

    @Column(name = "existing_account_number", length = 34)
    private String existingAccountNumber;

    @Column(name = "contact_full_name", nullable = false, length = 160)
    private String contactFullName;

    @Column(name = "contact_role", nullable = false, length = 120)
    private String contactRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BusinessApplicationEntity() {
        // JPA.
    }

    private BusinessApplicationEntity(UUID applicationId, Details details) {
        this.applicationId = applicationId;
        this.companyName = details.companyName();
        this.registrationNumber = details.registrationNumber();
        this.tin = details.tin();
        this.businessType = details.businessType();
        this.sector = details.sector();
        this.address = details.address();
        this.companyEmail = details.companyEmail();
        this.companyPhone = details.companyPhone();
        this.existingAccountNumber = blankToNull(details.existingAccountNumber());
        this.contactFullName = details.contactFullName();
        this.contactRole = details.contactRole();
        this.createdAt = Instant.now();
    }

    /**
     * The company columns, as one argument.
     *
     * <p>A record rather than a thirteen-argument constructor. Eleven of those arguments
     * are strings, and a factory that takes eleven strings in a fixed order is a factory
     * whose caller will eventually swap two of them — putting a TIN in the registration
     * number field, which nothing downstream could detect.
     */
    public record Details(
            String companyName,
            String registrationNumber,
            String tin,
            String businessType,
            String sector,
            String address,
            String companyEmail,
            String companyPhone,
            String existingAccountNumber,
            String contactFullName,
            String contactRole) {}

    public static BusinessApplicationEntity of(UUID applicationId, Details details) {
        return new BusinessApplicationEntity(applicationId, details);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID applicationId() {
        return applicationId;
    }

    public String companyName() {
        return companyName;
    }

    public String registrationNumber() {
        return registrationNumber;
    }

    public String tin() {
        return tin;
    }

    public String businessType() {
        return businessType;
    }

    public String sector() {
        return sector;
    }

    public String address() {
        return address;
    }

    public String companyEmail() {
        return companyEmail;
    }

    public String companyPhone() {
        return companyPhone;
    }

    public String existingAccountNumber() {
        return existingAccountNumber;
    }

    public String contactFullName() {
        return contactFullName;
    }

    public String contactRole() {
        return contactRole;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
