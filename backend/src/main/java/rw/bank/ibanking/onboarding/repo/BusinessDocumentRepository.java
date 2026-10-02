package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.BusinessDocumentEntity;

/** The document names promised on business applications. The files are not held. */
public interface BusinessDocumentRepository extends JpaRepository<BusinessDocumentEntity, UUID> {

    List<BusinessDocumentEntity> findByApplicationId(UUID applicationId);

    List<BusinessDocumentEntity> findByApplicationIdIn(List<UUID> applicationIds);
}
