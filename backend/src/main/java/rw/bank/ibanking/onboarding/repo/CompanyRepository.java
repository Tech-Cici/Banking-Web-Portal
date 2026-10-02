package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.CompanyEntity;

/** Companies that bank here. */
public interface CompanyRepository extends JpaRepository<CompanyEntity, UUID> {

    Optional<CompanyEntity> findByApplicationId(UUID applicationId);

    /** For the join-a-business flow, when it is implemented. Codes are not guessable. */
    Optional<CompanyEntity> findByCodeIgnoreCase(String code);

    List<CompanyEntity> findByIdIn(List<UUID> ids);
}
