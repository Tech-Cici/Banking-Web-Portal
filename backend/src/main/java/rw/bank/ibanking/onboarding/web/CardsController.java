package rw.bank.ibanking.onboarding.web;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.CardEntity;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.service.CardService;

/**
 * The signed-in customer's cards, and stopping one.
 *
 * <p>SCOPED TO THE CALLER, always. The customer id comes from the authenticated principal
 * and never from the request, the same rule the service-request and transfer paths follow.
 *
 * <p>NOTHING SENSITIVE CROSSES THIS BOUNDARY. The view carries a masked number and nothing
 * else about the card itself: no BIN, no full PAN, no CVV, no expiry beyond the month and
 * year printed on the front. There is no field on the entity that could carry more.
 */
@RestController
@RequestMapping("/api/v1/cards")
class CardsController {

    private final CardService cards;
    private final CustomerAccountRepository accounts;

    CardsController(CardService cards, CustomerAccountRepository accounts) {
        this.cards = cards;
        this.accounts = accounts;
    }

    /**
     * A card as the customer sees it.
     *
     * <p>{@code linkedAccountMask} is resolved at read time from the account row rather than
     * copied onto the card when it was issued: an account's mask is a property of the
     * account, and two copies of it would be two things that can disagree.
     */
    record CardView(
            String id,
            String maskedPan,
            String brand,
            String cardType,
            String status,
            String linkedAccountMask,
            int expiryMonth,
            int expiryYear,
            String cardholderName,
            String ownerId) {}

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<CardView> mine() {
        UUID customerId = AuthController.currentCustomerId();
        return cards.forCustomer(customerId).stream().map(this::view).toList();
    }

    /**
     * Stops a card.
     *
     * <p>{@code hasRole("CUSTOMER")} rather than {@code authenticated()}: a session holding
     * MUST_CHANGE_PASSWORD is authenticated and must reach nothing but the change-password
     * screen.
     */
    @PostMapping("/{id}/block")
    @PreAuthorize("hasRole('CUSTOMER')")
    CardView block(@PathVariable UUID id) {
        return view(cards.block(AuthController.currentCustomerId(), id));
    }

    private CardView view(CardEntity card) {
        String accountMask =
                accounts.findById(card.accountId())
                        .map(account -> account.maskedNumber())
                        /* An account removed from the profile after the card was issued.
                           The card is still the customer's; the link to it is what is
                           missing, and saying so beats rendering an empty cell. */
                        .orElse("Account no longer listed");

        return new CardView(
                card.id().toString(),
                card.maskedPan(),
                card.brand(),
                card.cardType().name(),
                card.status().name(),
                accountMask,
                card.expiryMonth(),
                card.expiryYear(),
                card.cardholderName(),
                card.customerId().toString());
    }
}
