package rw.bank.ibanking.onboarding.repo;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.EmailVerificationEntity;

public interface EmailVerificationRepository
        extends JpaRepository<EmailVerificationEntity, UUID> {

    Optional<EmailVerificationEntity> findByToken(String token);

    /**
     * How many codes this address has been sent recently.
     *
     * <p>This is the rate limit. Without it the start endpoint is an open relay: it is
     * unauthenticated and it sends mail to any address given to it, so anyone could use
     * the bank to deliver mail to a stranger, from the bank's own domain.
     */
    long countByEmailIgnoreCaseAndCreatedAtAfter(String email, Instant since);
}
