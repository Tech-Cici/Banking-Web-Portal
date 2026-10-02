package rw.bank.ibanking.onboarding.repo;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.LoginChallengeEntity;

public interface LoginChallengeRepository extends JpaRepository<LoginChallengeEntity, UUID> {

    /** Rate limit: how many codes this customer has been sent recently. */
    long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant since);
}
