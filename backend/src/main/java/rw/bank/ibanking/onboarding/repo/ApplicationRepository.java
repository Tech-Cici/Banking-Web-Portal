package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.ApplicationEntity;
import rw.bank.ibanking.onboarding.domain.ApplicationStatus;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, UUID> {

    List<ApplicationEntity> findByStatusOrderBySubmittedAtDesc(ApplicationStatus status);

    List<ApplicationEntity> findAllByOrderBySubmittedAtDesc();

    Optional<ApplicationEntity> findByReference(String reference);

    /** For the staff overview's "registered, no account yet" figure. */
    long countByStatus(ApplicationStatus status);
}
