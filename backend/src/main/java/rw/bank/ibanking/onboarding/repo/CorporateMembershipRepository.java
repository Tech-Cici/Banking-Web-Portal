package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.CorporateMembershipEntity;

/**
 * Who may act for which company.
 *
 * <p>EVERY READ FILTERS ON `revokedAtIsNull`. A membership is revoked rather than deleted,
 * so a query that forgot to would hand a company's accounts back to somebody whose access
 * was taken away — which is the one thing this table exists to make possible.
 */
public interface CorporateMembershipRepository
        extends JpaRepository<CorporateMembershipEntity, UUID> {

    /**
     * The companies this person may act for, oldest grant first.
     *
     * <p>Ordered because the session has to pick one of them as the active company, and
     * "the first one" has to mean the same thing on every request — an unordered result
     * would let a customer with two memberships land in a different company from one page
     * load to the next.
     */
    List<CorporateMembershipEntity> findByCustomerIdAndRevokedAtIsNullOrderByGrantedAtAsc(
            UUID customerId);

    List<CorporateMembershipEntity> findByCompanyIdAndRevokedAtIsNull(UUID companyId);
}
