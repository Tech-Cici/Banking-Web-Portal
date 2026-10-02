package rw.bank.ibanking.onboarding.repo;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.StaffEntity;

public interface StaffRepository extends JpaRepository<StaffEntity, UUID> {

    Optional<StaffEntity> findByEmailIgnoreCase(String email);
}
