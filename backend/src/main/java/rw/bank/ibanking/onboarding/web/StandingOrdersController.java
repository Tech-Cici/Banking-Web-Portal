package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.StandingOrderEntity;
import rw.bank.ibanking.onboarding.domain.StandingOrderFrequency;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.service.StandingOrderService;

/**
 * The signed-in customer's standing orders.
 *
 * <p>SCOPED TO THE CALLER, always: the customer id comes from the authenticated principal
 * and never from the request.
 *
 * <p>THE AMOUNT IS PARSED AGAINST A CURRENCY, never taken as a floating-point number. The
 * client sends a decimal string and a currency code, and a currency that is not the source
 * account's own is refused rather than reinterpreted -- guessing which currency somebody
 * meant is guessing how much money moves.
 */
@RestController
@RequestMapping("/api/v1/standing-orders")
class StandingOrdersController {

    private final StandingOrderService orders;
    private final CustomerAccountRepository accounts;

    StandingOrdersController(StandingOrderService orders, CustomerAccountRepository accounts) {
        this.orders = orders;
        this.accounts = accounts;
    }

    /**
     * A decimal string and a currency, the shape every other controller here uses.
     *
     * <p>A STRING, NOT A NUMBER, and that is the point rather than a quirk. {@code
     * Money.parse} refuses a value with more precision than the currency has, so
     * "1000.555" in a two-decimal currency is rejected instead of being rounded into
     * somebody's payment. A number arriving from a browser has already been through
     * JavaScript's floating point, which is the one thing this project will not do with
     * money.
     */
    record MoneyView(String amount, String currency) {}

    /**
     * The same pair, arriving.
     *
     * <p>THE CURRENCY IS CARRIED, NOT ASSUMED. The caller says which currency the figure
     * is in and {@link #create} refuses one that is not the source account's, so a wrong
     * currency becomes a refusal instead of a payment in a currency nobody chose.
     */
    record MoneyIn(
            @NotBlank(message = "Enter an amount.") @Size(max = 32) String amount,
            @NotBlank @Size(min = 3, max = 3) String currency) {
        Money toMoney() {
            return Money.parse(amount, currency);
        }
    }

    /**
     * <p>{@code startOn} IS NOT REQUIRED HERE, and that is not an oversight. A frequency
     * shorter than a day has no start date to give -- a two-minute order begins at once --
     * so the rule is "required for a date-shaped frequency", which this annotation cannot
     * express and {@code StandingOrderService.firstOccurrence} does.
     */
    record NewStandingOrder(
            @NotNull(message = "Choose the account this is paid from.") UUID sourceAccountId,
            @NotNull(message = "Choose the account this pays into.") UUID destinationAccountId,
            @NotNull(message = "Enter an amount.") @Valid MoneyIn amount,
            @NotNull(message = "Choose how often it should pay.") StandingOrderFrequency frequency,
            LocalDate startOn,
            LocalDate endsOn,
            @NotBlank(message = "Add a reference so you can recognise this later.") @Size(max = 140)
                    String reference) {}

    /**
     * <p>{@code nextRunAt} IS AN INSTANT IN ISO-8601, not a date. A two-minute order is due
     * at a time, and a client that was handed only a date could not show the customer when
     * the next payment actually falls. The browser renders it in the reader's own zone.
     */
    record StandingOrderView(
            String id,
            String reference,
            String sourceAccountMask,
            String sourceAccountName,
            String destinationSummary,
            MoneyView amount,
            String frequency,
            String nextRunAt,
            String endsOn,
            String status,
            String ownerId) {}

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<StandingOrderView> mine() {
        return orders.forCustomer(AuthController.currentCustomerId()).stream()
                .map(this::view)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    StandingOrderView one(@PathVariable UUID id) {
        return view(orders.one(AuthController.currentCustomerId(), id));
    }

    /**
     * Sets one up.
     *
     * <p>THE CURRENCY CHECK IS THE SERVICE'S, not this method's. It used to be done here
     * too, by reading the source account before the service was called -- which meant
     * answering "what currency is this account in" for an id the caller had not yet been
     * shown to own. The service checks ownership first and the currency second, which is the
     * order those two questions belong in. The rule itself is unchanged: an amount whose
     * currency is not the account's is refused rather than reinterpreted, because guessing
     * which currency somebody meant is guessing how much money moves.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    StandingOrderView create(@Valid @RequestBody NewStandingOrder request) {
        UUID customerId = AuthController.currentCustomerId();

        var created =
                orders.create(
                        customerId,
                        new StandingOrderService.NewOrder(
                                request.sourceAccountId(),
                                request.destinationAccountId(),
                                request.amount().toMoney(),
                                request.frequency(),
                                request.startOn(),
                                request.endsOn(),
                                request.reference()));
        return view(created);
    }

    /** Pauses an active order, or resumes a paused one. */
    @PostMapping("/{id}/pause")
    @PreAuthorize("hasRole('CUSTOMER')")
    StandingOrderView togglePause(@PathVariable UUID id) {
        return view(orders.togglePause(AuthController.currentCustomerId(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('CUSTOMER')")
    void cancel(@PathVariable UUID id) {
        orders.cancel(AuthController.currentCustomerId(), id);
    }

    /**
     * <p>BOTH ENDS ARE NAMED BY PRODUCT, not only masked. Every account a customer holds
     * carries the same customer number, so every mask on their screen reads "**** 0001".
     * Telling a customer that money moves from **** 0001 to **** 0001 tells them nothing;
     * the product name is the only thing that distinguishes one from another.
     *
     * <p>NO BALANCE, on either side. This view is a schedule, not a statement.
     */
    private StandingOrderView view(StandingOrderEntity order) {
        var source = accounts.findById(order.sourceAccountId());
        String sourceMask = source.map(CustomerAccountEntity::maskedNumber).orElse("—");
        String sourceName =
                source.map(account -> account.accountType().label())
                        .orElse("Account no longer listed");

        String destination =
                accounts.findById(order.destinationAccountId())
                        .map(account -> account.accountType().label() + " · " + account.maskedNumber())
                        .orElse("Account no longer listed");

        return new StandingOrderView(
                order.id().toString(),
                order.reference(),
                sourceMask,
                sourceName,
                destination,
                new MoneyView(order.amount().toPlainString(), order.amount().currency()),
                order.frequency().name(),
                order.nextRunAt().toString(),
                order.endsOn() == null ? null : order.endsOn().toString(),
                order.status().name(),
                order.customerId().toString());
    }
}
