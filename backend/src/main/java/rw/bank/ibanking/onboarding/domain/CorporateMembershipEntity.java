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
 * One person's authority to act for one company.
 *
 * <p>THIS IS WHAT GRANTS ACCESS TO A COMPANY'S ACCOUNTS — not the accounts' own columns,
 * and not the customer's type. A corporate customer sees a company's accounts because a
 * live membership says they may, and stops seeing them the moment it is revoked, with
 * nothing about the accounts changing.
 *
 * <p>That indirection is the reason this table exists rather than a {@code company_id} on
 * the customer. Access is a thing that is granted and taken away, by a named person, on a
 * date; a column on the customer records the current state and nothing about how it got
 * there.
 *
 * <p>REVOKED, NEVER DELETED. The first question after a wrong access grant is who granted
 * it and when, and a deleted row cannot answer it. {@link #isActive()} is what every read
 * must filter on.
 */
@Entity
@Table(name = "corporate_memberships")
public class CorporateMembershipEntity {

    @Id private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /**
     * A label, not the authority.
     *
     * <p>Permissions are derived from this in one place and checked by the server on every
     * request. Nothing is permitted because this column says ADMIN; it is permitted
     * because the permission check passed.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CorporateRole role;

    @Column(name = "granted_by", nullable = false, length = 160)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "revoked_by", length = 160)
    private String revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 500)
    private String revocationReason;

    protected CorporateMembershipEntity() {
        // JPA.
    }

    private CorporateMembershipEntity(
            UUID companyId, UUID customerId, CorporateRole role, String grantedBy) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.customerId = customerId;
        this.role = role;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    /**
     * Grants access.
     *
     * @param grantedBy the name of the member of staff, or the company administrator, who
     *     granted it. Required rather than nullable: an access grant to a company's money
     *     with nobody's name against it is the one this record exists to prevent.
     */
    public static CorporateMembershipEntity granted(
            UUID companyId, UUID customerId, CorporateRole role, String grantedBy) {
        return new CorporateMembershipEntity(companyId, customerId, role, grantedBy);
    }

    /** Whether this person may still act for the company. */
    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke(String by, String reason) {
        if (!isActive()) {
            throw new BusinessRuleException("That access has already been removed.");
        }
        this.revokedBy = by;
        this.revokedAt = Instant.now();
        this.revocationReason = reason;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID customerId() {
        return customerId;
    }

    public CorporateRole role() {
        return role;
    }

    public String grantedBy() {
        return grantedBy;
    }

    public Instant grantedAt() {
        return grantedAt;
    }

    public String revokedBy() {
        return revokedBy;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public String revocationReason() {
        return revocationReason;
    }
}
