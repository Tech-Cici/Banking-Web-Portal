package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.SignInEntity;

public interface SignInRepository extends JpaRepository<SignInEntity, UUID> {

    List<SignInEntity> findByCustomerIdOrderBySignedInAtDesc(UUID customerId, Limit limit);
}
