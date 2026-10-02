package rw.bank.ibanking.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A registration somebody submitted. Not an account — a request for one.
 *
 * <p>Entities never leave the service layer; controllers speak DTOs (see the README's
 * layering note). That is why there are no Jackson annotations here.
 */
@Entity
@Table(name = "applications")
public class ApplicationEntity {

    @Id private UUID id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    protected ApplicationEntity() {
        // JPA.
    }

    private ApplicationEntity(
            UUID id,
            String reference,
            ApplicationKind kind,
            String displayName,
            String email,
            String phone,
            boolean emailVerified) {
        this.id = id;
        this.reference = reference;
        this.kind = kind;
        this.status = ApplicationStatus.SUBMITTED;
        this.displayName = displayName;
        this.email = email;
        this.phone = phone;
        this.emailVerified = emailVerified;
        this.submittedAt = Instant.now();
    }

    /**
     * A newly submitted registration.
     *
     * @param emailVerified whether the applicant entered a code sent to that address.
     *     Required rather than defaulted: a new registration flow must not silently
     *     inherit "verified", because an administrator reads that field before issuing
     *     credentials.
     */
    public static ApplicationEntity submitted(
            String reference,
            ApplicationKind kind,
            String displayName,
            String email,
            String phone,
            boolean emailVerified) {
        return new ApplicationEntity(
                UUID.randomUUID(), reference, kind, displayName, email, phone, emailVerified);
    }

    public void personalDetails(String accountNumber, String nationalId, LocalDate dateOfBirth) {
        this.accountNumber = accountNumber;
        this.nationalId = nationalId;
        this.dateOfBirth = dateOfBirth;
    }

    /** Records that an administrator created the login. */
    public void accountCreated(UUID newCustomerId) {
        this.customerId = newCustomerId;
        this.status = ApplicationStatus.ACCOUNT_CREATED;
    }

    public void approved() {
        this.status = ApplicationStatus.APPROVED;
    }

    public void rejected(String reason) {
        this.status = ApplicationStatus.REJECTED;
        this.rejectionReason = reason;
    }

    public UUID id() {
        return id;
    }

    public String reference() {
        return reference;
    }

    public ApplicationKind kind() {
        return kind;
    }

    public ApplicationStatus status() {
        return status;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public String accountNumber() {
        return accountNumber;
    }

    public String nationalId() {
        return nationalId;
    }

    public LocalDate dateOfBirth() {
        return dateOfBirth;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public UUID customerId() {
        return customerId;
    }

    public String rejectionReason() {
        return rejectionReason;
    }
}
