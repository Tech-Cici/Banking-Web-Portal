package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.TrustedDeviceEntity;

/** Browsers a customer has already proved once. */
public interface TrustedDeviceRepository extends JpaRepository<TrustedDeviceEntity, UUID> {

    /**
     * By hash, which is unique.
     *
     * <p>Deliberately NOT keyed on the customer as well. Looking a token up on its own
     * and then comparing the customer means a token issued to somebody else is found and
     * then refused — which is what lets the refusal be logged. A query scoped to the
     * customer would return nothing and the attempt would be indistinguishable from a
     * browser that had simply never been trusted.
     */
    Optional<TrustedDeviceEntity> findByTokenHash(String tokenHash);

    /** Everything ever trusted for this customer, revoked or not. */
    List<TrustedDeviceEntity> findByCustomerId(UUID customerId);
}
