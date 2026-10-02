package rw.bank.ibanking.onboarding.repo;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccountEntity, UUID> {

    /**
     * The account, locked for update.
     *
     * <p>THE RACE THIS CLOSES. Two withdrawals of 800 against a balance of 1000, arriving
     * together: both read 1000, both decide there is enough, both subtract, and the
     * account ends at -600 or at 200 depending on which write lands last. Either way the
     * bank has paid out 1600 it did not have.
     *
     * <p>A pessimistic write lock makes the second request wait for the first to commit,
     * so it reads the balance the first one left. Slower, and the only version that is
     * correct — an optimistic version would have to fail and retry, which for a payment
     * means telling somebody their money may or may not have moved.
     */
    /**
     * A company's accounts, whoever they were entered against.
     *
     * <p>Keyed on the COMPANY, not on a customer. That is what lets a colleague added to
     * the company later see exactly the same accounts, with nothing copied and no second
     * balance to keep in step.
     */
    List<CustomerAccountEntity> findByCompanyIdInAndRemovedAtIsNullOrderByAssignedAtAsc(
            List<UUID> companyIds);

    /** A person's own accounts. Company accounts are excluded by the null check. */
    List<CustomerAccountEntity>
            findByCustomerIdAndCompanyIdIsNullAndRemovedAtIsNullOrderByAssignedAtAsc(
                    UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CustomerAccountEntity a where a.id = :id")
    Optional<CustomerAccountEntity> findByIdForUpdate(@Param("id") UUID id);

    /** What the customer can see, oldest first so the list does not reshuffle. */
    List<CustomerAccountEntity> findByCustomerIdAndRemovedAtIsNullOrderByAssignedAtAsc(
            UUID customerId);

    /** The same for many customers, in one query — the staff list renders every row. */
    List<CustomerAccountEntity> findByCustomerIdInAndRemovedAtIsNullOrderByAssignedAtAsc(
            List<UUID> customerIds);

    /**
     * Is this account already on somebody's profile?
     *
     * <p>By the FULL number, because that is the only thing that identifies an account —
     * masks are not unique. Removed rows are excluded: an account entered against the
     * wrong customer must be usable for the right one afterwards.
     */
    List<CustomerAccountEntity> findByAccountNumberAndRemovedAtIsNull(String accountNumber);
}
