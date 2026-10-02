package rw.bank.ibanking.onboarding.repo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.bank.ibanking.onboarding.domain.ServiceRequestEntity;
import rw.bank.ibanking.onboarding.domain.ServiceRequestStatus;
import rw.bank.ibanking.onboarding.domain.ServiceRequestType;

/** Cards and cheque books customers have asked for. */
public interface ServiceRequestRepository extends JpaRepository<ServiceRequestEntity, UUID> {

    /**
     * One customer's own requests, newest first.
     *
     * <p>Newest first because this is a list the customer reads rather than a queue
     * anybody works through: the request they just made is the one they came to check.
     */
    List<ServiceRequestEntity> findByCustomerIdOrderBySubmittedAtDesc(UUID customerId);

    /**
     * The staff queue: everything still open, oldest first.
     *
     * <p>Oldest first because this one IS a queue. Newest first buries whoever has been
     * waiting longest under everybody who asked this morning.
     */
    List<ServiceRequestEntity> findByStatusInOrderBySubmittedAtAsc(
            List<ServiceRequestStatus> statuses);

    /**
     * How many requests are open across the bank, for the staff overview.
     *
     * <p>This count was here, was deleted as dead code when nothing called it, and is back
     * because the overview now does. That is the right sequence rather than an oversight:
     * an unused query is a maintenance cost and a half-built feature, and it was cheaper to
     * delete it and bring it back than to leave it sitting unexercised.
     */
    long countByStatusIn(List<ServiceRequestStatus> statuses);

    /**
     * How many open requests this customer has, for the per-customer cap.
     *
     * <p>COUNTED IN THE DATABASE. The first version of the caller loaded every request the
     * customer had ever made, filtered it in Java and counted the result — fine with three
     * rows and a table scan per button press once somebody has a few years of history.
     * The same point PasswordResetRequestRepository makes about its rate limit.
     */
    long countByCustomerIdAndStatusIn(UUID customerId, List<ServiceRequestStatus> statuses);

    /**
     * Whether this customer already has an open request of this kind for this account.
     *
     * <p>WHAT IT PREVENTS: somebody pressing "Request card" four times and the bank
     * printing four cards. A duplicate is refused with a sentence naming the existing
     * reference, which is more use than silently making a second row — the customer rang
     * because they could not see the first one, and the answer is to show it to them.
     *
     * <p>Scoped to OPEN requests, so a customer whose card was declined, or who collected
     * one last year and needs a replacement, can ask again.
     */
    List<ServiceRequestEntity> findByCustomerIdAndRequestTypeAndAccountIdAndStatusIn(
            UUID customerId,
            ServiceRequestType requestType,
            UUID accountId,
            List<ServiceRequestStatus> statuses);
}
