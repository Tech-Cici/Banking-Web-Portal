package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.BeneficiaryEntity;
import rw.bank.ibanking.onboarding.domain.BeneficiaryStatus;

/** Saved payees, and the queue of ones waiting for a member of staff. */
public interface BeneficiaryRepository extends JpaRepository<BeneficiaryEntity, UUID> {

    /**
     * One customer's own payees, newest first.
     *
     * <p>Newest first because this is a list the customer reads, not a queue anybody works
     * through: the payee they just added is the one they are looking for, and it is the one
     * whose status they want to check.
     */
    List<BeneficiaryEntity> findByCustomerIdOrderByAddedAtDesc(UUID customerId);

    /**
     * The staff queue: everything waiting, oldest first.
     *
     * <p>Oldest first because this one IS a queue. Newest first would bury the customer who
     * has been unable to pay their landlord longest under everybody who added a payee this
     * morning.
     */
    List<BeneficiaryEntity> findByStatusOrderByAddedAtAsc(BeneficiaryStatus status);

    long countByStatus(BeneficiaryStatus status);

    /**
     * Whether this customer already has a payee at this destination that has not been
     * refused.
     *
     * <p>THIS IS WHAT KEEPS "ONE PAYEE PER CUSTOMER PER DESTINATION" TRUE. It would be a
     * partial unique index on {@code (customer_id, account_number) WHERE status <>
     * 'REFUSED'} if the test suite's H2 supported a WHERE clause on an index — see V17 —
     * so the rule lives in the service and this is how it asks.
     *
     * <p>REFUSED ROWS ARE EXCLUDED ON PURPOSE, by the caller passing the two statuses that
     * are not refused. A customer who mistyped a digit, was refused, and wants to add the
     * corrected number must be able to; so must one whose refusal they have since sorted
     * out at a branch.
     */
    Optional<BeneficiaryEntity> findFirstByCustomerIdAndAccountNumberAndStatusIn(
            UUID customerId, String accountNumber, List<BeneficiaryStatus> statuses);

    /**
     * How many payees this customer has that are not refused, for the per-customer cap.
     *
     * <p>Counted in the database rather than by loading the list and calling {@code size()}
     * — the same point {@code PasswordResetRequestRepository} makes about its rate limit.
     */
    long countByCustomerIdAndStatusIn(UUID customerId, List<BeneficiaryStatus> statuses);
}
