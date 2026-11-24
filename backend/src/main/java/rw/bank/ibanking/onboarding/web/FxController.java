package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.fx.FxQuoteEntity;
import rw.bank.ibanking.onboarding.service.FxService;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;

/**
 * Converting between a customer's own currencies.
 *
 * <p>TWO STEPS, AND THE SECOND SENDS NO FIGURES. Getting a rate returns a quote with both
 * amounts worked out; confirming names that quote. The client never multiplies anything, so
 * there is no arithmetic for it to get wrong and nothing for a tampered request to change.
 *
 * <p>HERE RATHER THAN BESIDE ITS SERVICE, for the reason StatementsController records:
 * {@code AuthController.currentCustomerId} is package-private on purpose.
 */
@RestController
@RequestMapping("/api/v1/fx")
class FxController {

    private final FxService fx;
    private final CustomerAccountRepository accounts;

    FxController(FxService fx, CustomerAccountRepository accounts) {
        this.fx = fx;
        this.accounts = accounts;
    }

    /** A decimal string and a currency, the shape every money field here uses. */
    record MoneyIn(
            @NotBlank(message = "Enter an amount.") @Size(max = 32) String amount,
            @NotBlank @Size(min = 3, max = 3) String currency) {

        Money toMoney() {
            return Money.parse(amount, currency);
        }
    }

    record MoneyView(String amount, String currency) {
        static MoneyView of(Money money) {
            return new MoneyView(money.toPlainString(), money.currency());
        }
    }

    record QuoteRequest(
            @NotNull(message = "Choose what to sell.") @Valid MoneyIn sellAmount,
            @NotBlank(message = "Choose what to buy.")
                    @Pattern(
                            regexp = "[A-Za-z]{3}",
                            message = "A currency is a three-letter code, such as USD.")
                    String buyCurrency) {}

    /**
     * <p>{@code fee} IS ALWAYS ZERO AND THAT IS NOT A PLACEHOLDER. The bank's margin is the
     * gap between the rate it buys at and the rate it sells at, which is already inside the
     * two amounts. A fee beside it would be charging the customer twice on paper.
     *
     * <p>{@code rate} IS A SENTENCE, not a bare decimal. "1,450" does not say which way round
     * it runs, and the direction is the meaning.
     */
    record QuoteView(
            String quoteId,
            MoneyView sellAmount,
            MoneyView buyAmount,
            String rate,
            String rateExpiresAt,
            MoneyView fee) {

        static QuoteView of(FxQuoteEntity quote) {
            return new QuoteView(
                    quote.id().toString(),
                    MoneyView.of(quote.sellAmount()),
                    MoneyView.of(quote.buyAmount()),
                    quote.rateText(),
                    quote.expiresAt().toString(),
                    MoneyView.of(new Money(0L, quote.sellAmount().currency())));
        }
    }

    /**
     * <p>BOTH ACCOUNTS ARE NAMED. The destination used to be worked out from the bought
     * currency, which quietly meant "the oldest account you hold in it" for anybody with more
     * than one -- and a fixed deposit is not somewhere a conversion should land by default.
     */
    record ConvertRequest(
            @NotNull(message = "Get a rate first.") UUID quoteId,
            @NotNull(message = "Choose the account the money comes from.") UUID sourceAccountId,
            @NotNull(message = "Choose the account the money goes into.")
                    UUID destinationAccountId) {}

    /** What the portal shows after a movement. */
    record ReceiptView(
            String id,
            String reference,
            String status,
            MoneyView amount,
            MoneyView fee,
            MoneyView totalDebit,
            String sourceAccountMask,
            String destinationSummary,
            String submittedAt,
            boolean awaitingApproval) {}

    @PostMapping("/quote")
    @PreAuthorize("hasRole('CUSTOMER')")
    QuoteView quote(@Valid @RequestBody QuoteRequest request) {
        return QuoteView.of(
                fx.quote(
                        AuthController.currentCustomerId(),
                        request.sellAmount().toMoney(),
                        request.buyCurrency()));
    }

    @GetMapping("/quote/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    QuoteView one(@PathVariable UUID id) {
        return QuoteView.of(fx.quoteFor(AuthController.currentCustomerId(), id));
    }

    /**
     * <p>THE KEY IS BOUNDED AT 64, NOT 80. Two ledger entries are written from it, each with a
     * suffix naming its leg, and account_transactions.idempotency_key is VARCHAR(80). A key
     * accepted at the full column width would overflow once a suffix was added -- which is a
     * mistake this project has already made once, on the standing-order sweep, where it
     * surfaced as a payment that silently never happened.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    ReceiptView convert(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody ConvertRequest request) {

        UUID customerId = AuthController.currentCustomerId();

        FxQuoteEntity done =
                fx.convert(
                        customerId,
                        request.quoteId(),
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        idempotencyKey);

        Money sold = done.sellAmount();
        Money bought = done.buyAmount();

        String sourceMask =
                accounts.findById(request.sourceAccountId())
                        .map(CustomerAccountEntity::maskedNumber)
                        .orElse("—");

        return new ReceiptView(
                done.id().toString(),
                done.rateText(),
                /*
                 * COMPLETED, because it is. The money moved inside the request: nothing is
                 * queued, nothing is waiting for a manager, and there is no later moment at
                 * which this could turn out not to have happened.
                 */
                "COMPLETED",
                MoneyView.of(sold),
                MoneyView.of(new Money(0L, sold.currency())),
                MoneyView.of(sold),
                sourceMask,
                /*
                 * NAMES THE ACCOUNT IT LANDED IN. "Into your USD account" is not an answer for
                 * a customer who holds two, and the first question asked of the first
                 * conversion this portal ever did was exactly which one.
                 */
                bought.toPlainString()
                        + " "
                        + bought.currency()
                        + " into "
                        + accounts.findById(request.destinationAccountId())
                                .map(a -> a.accountType().label() + " " + a.maskedNumber())
                                .orElse("your account"),
                java.time.Instant.now().toString(),
                false);
    }
}
