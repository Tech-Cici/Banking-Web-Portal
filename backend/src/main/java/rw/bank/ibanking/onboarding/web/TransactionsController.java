package rw.bank.ibanking.onboarding.web;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.service.AccountLedgerService;
import rw.bank.ibanking.onboarding.service.OnboardingService;

/**
 * Activity across every account the signed-in customer holds.
 *
 * <p>Its own controller rather than a method on {@code AccountsController}, because the
 * path is {@code /transactions/recent} and not under {@code /accounts} — the dashboard asks
 * "what has happened lately", not "what has happened on this account".
 *
 * <p>SCOPED BY CONSTRUCTION. The account ids are fetched for the authenticated customer
 * first and the query is then restricted to that list, so there is no code path that could
 * return another customer's entry — not a check that a later change could drop, but the
 * shape of the query itself.
 */
@RestController
@RequestMapping("/api/v1/transactions")
class TransactionsController {

    private static final int MAX_LIMIT = 50;

    private final OnboardingService onboarding;
    private final AccountLedgerService ledger;

    TransactionsController(OnboardingService onboarding, AccountLedgerService ledger) {
        this.onboarding = onboarding;
        this.ledger = ledger;
    }

    @GetMapping("/recent")
    @PreAuthorize("hasRole('CUSTOMER')")
    List<AccountsController.TransactionView> recent(
            @RequestParam(defaultValue = "8") int limit, Authentication caller) {

        UUID customerId = UUID.fromString(caller.getName());

        List<UUID> accountIds =
                onboarding.accountsFor(customerId).stream()
                        .map(CustomerAccountEntity::id)
                        .toList();

        return ledger.recentAcross(accountIds, Math.min(Math.max(limit, 1), MAX_LIMIT)).stream()
                .map(AccountsController.TransactionView::of)
                .toList();
    }
}
