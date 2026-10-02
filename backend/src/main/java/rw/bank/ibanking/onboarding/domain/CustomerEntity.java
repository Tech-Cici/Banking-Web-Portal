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
 * A customer login, created by an administrator and released by a manager.
 *
 * <p>The four-eyes rule is enforced in three places on purpose: here, in the service that
 * calls {@link #approve(String)}, and as a CHECK constraint in the migration. Defence in
 * depth is warranted because the thing being prevented — one insider both creating and
 * activating credentials for a customer who does not exist — is the highest-value attack
 * available from inside a bank.
 */
@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id private UUID id;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(name = "customer_number", nullable = false, unique = true)
    private String customerNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * When an emailed temporary password stops working.
     *
     * <p>Null means no expiry applies — either it was handed over in person, or the
     * customer has already chosen their own. See V3__temporary_password_expiry.sql.
     */
    @Column(name = "temporary_password_expires_at")
    private Instant temporaryPasswordExpiresAt;

    /* ------------------------------------------------------------- freezing */

    @Column(name = "frozen_by")
    private String frozenBy;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "freeze_reason")
    private String freezeReason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * RETAIL or CORPORATE, and it is a decision recorded at creation.
     *
     * <p>Not derived from "has a corporate membership". The two would usually agree, and
     * the day they did not the disagreement would decide what this customer can see — a
     * retail customer who somehow gained a membership would silently become a corporate
     * one. The type says what kind of login this is; the membership says what it grants.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 16)
    private UserType userType;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected CustomerEntity() {
        // JPA.
    }

    private CustomerEntity(
            UUID applicationId,
            String fullName,
            String email,
            String phone,
            String customerNumber,
            String passwordHash,
            String createdBy,
            UserType userType) {
        this.id = UUID.randomUUID();
        this.userType = userType;
        this.applicationId = applicationId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.customerNumber = customerNumber;
        this.status = CustomerStatus.PENDING_APPROVAL;
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * Creates the login in its inert state.
     *
     * <p>PENDING_APPROVAL and mustChangePassword are set here rather than passed in, so no
     * caller can create an account that is already active or already trusted.
     */
    public static CustomerEntity pendingApproval(
            UUID applicationId,
            String fullName,
            String email,
            String phone,
            String customerNumber,
            String temporaryPasswordHash,
            String createdByName) {
        return new CustomerEntity(
                applicationId,
                fullName,
                email,
                phone,
                customerNumber,
                temporaryPasswordHash,
                createdByName,
                UserType.RETAIL);
    }

    /**
     * The same inert login, for somebody who will act for a company.
     *
     * <p>A separate factory rather than a boolean argument, because the two produce
     * customers with different authority and a caller that passed the wrong boolean would
     * be hard to spot at the call site. Being CORPORATE grants nothing on its own — the
     * membership created alongside it is what does, and the server checks the permissions
     * that membership implies on every request.
     */
    public static CustomerEntity corporatePendingApproval(
            UUID applicationId,
            String fullName,
            String email,
            String phone,
            String customerNumber,
            String temporaryPasswordHash,
            String createdByName) {
        return new CustomerEntity(
                applicationId,
                fullName,
                email,
                phone,
                customerNumber,
                temporaryPasswordHash,
                createdByName,
                UserType.CORPORATE);
    }

    /**
     * Releases the account.
     *
     * @throws BusinessRuleException if the approver is the person who created it, or if it
     *     has already been decided. Both are refused here rather than only in the service,
     *     so the rule travels with the object.
     */
    public void approve(String approverName) {
        if (status != CustomerStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException(
                    "Another manager has already approved or rejected this account. Refresh the"
                            + " page to see the result.");
        }
        if (createdBy.equalsIgnoreCase(approverName)) {
            throw new BusinessRuleException(
                    "You created this account, so you cannot approve it. It needs a different"
                            + " manager.");
        }
        this.status = CustomerStatus.ACTIVE;
        this.approvedBy = approverName;
        this.approvedAt = Instant.now();
    }

    /** Turns the account down and destroys the temporary password with it. */
    public void reject(String approverName, String unusablePasswordHash) {
        if (status != CustomerStatus.PENDING_APPROVAL) {
            throw new BusinessRuleException(
                    "Another manager has already approved or rejected this account. Refresh the"
                            + " page to see the result.");
        }
        this.status = CustomerStatus.REJECTED;
        this.approvedBy = approverName;
        this.approvedAt = Instant.now();

        /*
         * The temporary password is overwritten, not merely orphaned. It was known to at
         * least one member of staff, and a rejected account that could later be
         * un-rejected would come back with a credential a stranger still knows.
         */
        this.passwordHash = unusablePasswordHash;
    }

    /**
     * Stops the customer using the service, at once.
     *
     * <p>Only an ACTIVE account can be frozen. Freezing one that is still awaiting
     * approval would be meaningless — it cannot be used anyway — and freezing a rejected
     * one would imply access existed to take away.
     *
     * <p>Note what this does NOT do: it does not touch the password. A freeze is not a
     * punishment and is very often temporary, so the customer's own credential survives
     * it and works again the moment the account is restored. Destroying it would turn
     * every precautionary freeze into a password reissue.
     *
     * @throws BusinessRuleException if the account is not ACTIVE
     */
    public void freeze(String managerName, String reason) {
        if (status != CustomerStatus.ACTIVE) {
            throw new BusinessRuleException(
                    "Only an active account can be frozen. This one is "
                            + status.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + ".");
        }
        this.status = CustomerStatus.SUSPENDED;
        this.frozenBy = managerName;
        this.frozenAt = Instant.now();
        this.freezeReason = reason;
    }

    /**
     * Gives the account back.
     *
     * <p>The reason is replaced rather than cleared, and {@code frozenBy} keeps the name
     * of whoever acted last. Restoring access should not erase the fact that access was
     * once removed: if an account is frozen and unfrozen repeatedly, that pattern is
     * itself worth being able to see.
     *
     * @throws BusinessRuleException if the account is not frozen
     */
    public void unfreeze(String managerName, String reason) {
        if (status != CustomerStatus.SUSPENDED) {
            throw new BusinessRuleException("This account is not frozen.");
        }
        this.status = CustomerStatus.ACTIVE;
        this.frozenBy = managerName;
        this.frozenAt = Instant.now();
        this.freezeReason = reason;
    }

    public String frozenBy() {
        return frozenBy;
    }

    public Instant frozenAt() {
        return frozenAt;
    }

    public String freezeReason() {
        return freezeReason;
    }

    /**
     * Replaces the temporary password with one the customer chose.
     *
     * <p>Overwrites rather than adds. Leaving the issued password usable would mean the
     * account has two passwords, one of which the customer never chose and a member of
     * staff has seen.
     */
    public void replacePassword(String newHash) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
        // Nothing left to expire once the password is the customer's own.
        this.temporaryPasswordExpiresAt = null;
    }

    /**
     * Sets the temporary password the approval email will carry.
     *
     * <p>Called at APPROVAL, not at creation. The plaintext exists only inside that one
     * call and the message it composes: it is hashed here and nowhere else holds it, so
     * no member of staff ever sees a working credential for a customer's account.
     */
    public void issueTemporaryPassword(String hash, Instant expiresAt) {
        this.passwordHash = hash;
        this.mustChangePassword = true;
        this.temporaryPasswordExpiresAt = expiresAt;
    }

    /**
     * Whether an emailed temporary password has gone stale.
     *
     * <p>Only ever true while the password is still the bank's. A customer who has chosen
     * their own is never locked out by this, which is why {@link #replacePassword} clears
     * the timestamp rather than leaving it behind.
     */
    public boolean temporaryPasswordExpired(Instant now) {
        return mustChangePassword
                && temporaryPasswordExpiresAt != null
                && now.isAfter(temporaryPasswordExpiresAt);
    }

    public Instant temporaryPasswordExpiresAt() {
        return temporaryPasswordExpiresAt;
    }

    public boolean canSignIn() {
        return status == CustomerStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID applicationId() {
        return applicationId;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public String customerNumber() {
        return customerNumber;
    }

    public CustomerStatus status() {
        return status;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UserType userType() {
        return userType;
    }

    public String approvedBy() {
        return approvedBy;
    }

    public Instant approvedAt() {
        return approvedAt;
    }
}
