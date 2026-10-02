package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.BusinessSignatoryEntity;

/** The signatories listed on business applications. */
public interface BusinessSignatoryRepository extends JpaRepository<BusinessSignatoryEntity, UUID> {

    /** In the order the applicant listed them — the first is conventionally the primary. */
    List<BusinessSignatoryEntity> findByApplicationIdOrderByOrdinalAsc(UUID applicationId);

    List<BusinessSignatoryEntity> findByApplicationIdInOrderByOrdinalAsc(List<UUID> applicationIds);
}
