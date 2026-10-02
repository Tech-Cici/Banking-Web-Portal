package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.BusinessApplicationEntity;

/** The company details attached to business applications. */
public interface BusinessApplicationRepository
        extends JpaRepository<BusinessApplicationEntity, UUID> {

    Optional<BusinessApplicationEntity> findByApplicationId(UUID applicationId);

    /**
     * For a staff list showing several applications at once.
     *
     * <p>One query rather than one per row. The registrations screen renders every
     * application it was given, so a per-row lookup is a query count that grows with the
     * bank's intake — the classic list that is fast on a developer's three rows and slow
     * on a branch's three hundred.
     */
    List<BusinessApplicationEntity> findByApplicationIdIn(List<UUID> applicationIds);
}
