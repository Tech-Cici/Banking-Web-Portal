package rw.bank.ibanking.onboarding.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.onboarding.domain.CorporateMembershipEntity;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.repo.CorporateMembershipRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;

/**
 * WHICH ACCOUNTS A SIGNED-IN CUSTOMER MAY SEE AND ACT ON. One rule, one place.
 *
 * <p>This exists as its own class for a single reason: the rule is now two rules, and two
 * rules written out twice is how one of them drifts. An account is reachable if
 *
 * <ul>
 *   <li>it is PERSONAL and held by this customer — {@code company_id} is null and
 *       {@code customer_id} is theirs; or
 *   <li>it belongs to a COMPANY this customer has a live membership of.
 * </ul>
 *
 * <p>Before companies existed the rule was one comparison, so the reads did it inline and
 * the ledger did it again with a {@code filter}. Adding companies to those two call sites
 * separately would mean the screen and the payment disagreeing about whose money it is —
 * and the disagreement that matters is the one where the payment is more permissive.
 *
 * <p>{@link #mayAct} IS THE MONEY CONTROL. Everything here filters on a LIVE membership:
 * a revoked one is kept for the audit trail, so a query that forgot the revocation check
 * would hand a company's accounts back to somebody whose access was deliberately taken
 * away. Revoked access that still works is worse than access that was never granted,
 * because somebody has already decided it should stop.
 *
 * <p>It deliberately depends on repositories only. {@code OnboardingService} already
 * depends on {@code AccountLedgerService}, so a shared rule living in either of those
 * would be a cycle; this has no service dependencies and both can use it.
 */
@Service
public class AccountAccess {

    private final CustomerAccountRepository accounts;
    private final CorporateMembershipRepository memberships;

    AccountAccess(CustomerAccountRepository accounts, CorporateMembershipRepository memberships) {
        this.accounts = accounts;
        this.memberships = memberships;
    }

    /** The companies this customer may currently act for. Empty for a personal customer. */
    @Transactional(readOnly = true)
    public List<UUID> companiesFor(UUID customerId) {
        return memberships.findByCustomerIdAndRevokedAtIsNullOrderByGrantedAtAsc(customerId).stream()
                .map(CorporateMembershipEntity::companyId)
                .toList();
    }

    /**
     * Everything this customer can reach: their own accounts, plus their companies'.
     *
     * <p>Two queries rather than one clever join, because the two halves answer different
     * questions and a single query would have to express "mine, or my companies'" in a
     * way that is easy to get subtly wrong. The second is skipped entirely for a personal
     * customer, which is every customer today.
     *
     * <p>OLDEST FIRST, across both halves. The list is what the dashboard renders, and an
     * order that depends on which query a row came from would reshuffle the cards for a
     * customer who is a member of a company — the same complaint the ordering on the
     * original query was added to fix.
     */
    @Transactional(readOnly = true)
    public List<CustomerAccountEntity> accountsFor(UUID customerId) {
        List<CustomerAccountEntity> own =
                accounts.findByCustomerIdAndCompanyIdIsNullAndRemovedAtIsNullOrderByAssignedAtAsc(
                        customerId);

        List<UUID> companyIds = companiesFor(customerId);
        if (companyIds.isEmpty()) return own;

        List<CustomerAccountEntity> corporate =
                accounts.findByCompanyIdInAndRemovedAtIsNullOrderByAssignedAtAsc(companyIds);

        return Stream.concat(own.stream(), corporate.stream())
                .sorted(Comparator.comparing(CustomerAccountEntity::assignedAt))
                .toList();
    }

    /**
     * Whether this customer may move money on this account.
     *
     * <p>THE SAME RULE AS {@link #accountsFor}, deliberately — if a screen can show it,
     * the holder can act on it, and nothing else can. Written as its own method rather
     * than as "is it in the list" so a caller holding one account does not have to load
     * every account to find out.
     *
     * <p>A removed account is reachable by nobody: it was taken off the profile because
     * somebody decided it should not be there, and the balance is still on the row.
     */
    @Transactional(readOnly = true)
    public boolean mayAct(UUID customerId, CustomerAccountEntity account) {
        if (!account.isActive()) return false;

        if (!account.isCompanyAccount()) {
            return account.customerId().equals(customerId);
        }

        /*
         * A company account. `customer_id` on the row is whoever the administrator was
         * creating a login for at the time and grants NOTHING here — otherwise the
         * contact would keep access after their membership was revoked, which is exactly
         * the case revocation exists for.
         */
        return companiesFor(customerId).contains(account.companyId());
    }
}
