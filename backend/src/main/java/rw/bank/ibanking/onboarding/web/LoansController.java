package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.collateral.CollateralDeclaration;
import rw.bank.ibanking.collateral.CollateralFieldKey;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.config.LoanProperties;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.onboarding.domain.LoanEntity;
import rw.bank.ibanking.onboarding.domain.LoanRepaymentEntity;
import rw.bank.ibanking.onboarding.domain.ServiceRequestEntity;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.service.LoanService;
import rw.bank.ibanking.onboarding.service.ServiceRequestService;

/**
 * The customer's loans, what a new one would cost, and applying for one.
 *
 * <p>THE SIMULATION IS LABELLED INDICATIVE ON THE SCREEN AND IS NOT STORED HERE. It is an
 * answer to a question, not a quote the bank has given, and nothing in this service treats
 * it as one afterwards.
 */
@RestController
@RequestMapping("/api/v1/loans")
class LoansController {

    private final LoanService loans;
    private final ServiceRequestService serviceRequests;
    private final CustomerAccountRepository accounts;
    private final LoanProperties products;

    LoansController(
            LoanService loans,
            ServiceRequestService serviceRequests,
            CustomerAccountRepository accounts,
            LoanProperties products) {
        this.loans = loans;
        this.serviceRequests = serviceRequests;
        this.accounts = accounts;
        this.products = products;
    }

    /** A decimal string and a currency — the shape every other controller here uses. */
    record MoneyView(String amount, String currency) {}

    record LoanView(
            String id,
            String product,
            String reference,
            String currency,
            MoneyView principal,
            MoneyView outstandingBalance,
            MoneyView installment,
            String nextDueDate,
            String interestRatePercent,
            String status,
            String ownerId) {}

    record SimulationRequest(
            @NotNull @Valid MoneyIn amount,
            @Min(1) @Max(600) int months,
            @NotBlank @Size(max = 32) String product) {}

    record MoneyIn(@NotBlank @Size(max = 32) String amount, @NotBlank @Size(min = 3, max = 3) String currency) {
        Money toMoney() {
            return Money.parse(amount, currency);
        }
    }

    record SimulationView(
            MoneyView principal,
            int months,
            String product,
            String interestRatePercent,
            MoneyView monthlyInstallment,
            MoneyView totalInterest,
            MoneyView totalRepayable) {}

    record ApplicationRequest(
            @NotNull @Valid MoneyIn amount,
            @Min(1) @Max(600) int months,
            @NotBlank @Size(max = 32) String product,
            @NotNull UUID disburseToAccountId,
            @NotBlank(message = "Say what the loan is for.") @Size(max = 120) String purpose,
            /*
             * ABSENT IS NOT THE SAME AS EMPTY HERE ONLY IN WHAT IT SUGGESTS. Either way the
             * service decides whether this product and amount needed security; the list is
             * never trusted to say that it did not.
             */
            @Size(max = 5) List<@Valid CollateralIn> collateral) {}

    /**
     * One pledged item, as the form submits it.
     *
     * <p>THE VALUES ARE KEYED BY FIELD NAME rather than being named columns, because which
     * fields exist depends on what is being pledged -- a parcel has a UPI, a vehicle has a
     * plate and a chassis number. A key the kind does not ask for is ignored; one it does ask
     * for and that is missing is refused by name.
     */
    record CollateralIn(
            @NotNull UUID documentId,
            @NotBlank(message = "Say what you are pledging.") @Size(max = 160) String description,
            @NotNull Map<String, @Size(max = 200) String> values) {

        CollateralDeclaration toDeclaration() {
            Map<CollateralFieldKey, String> parsed = new EnumMap<>(CollateralFieldKey.class);

            values.forEach(
                    (key, value) -> {
                        /*
                         * A KEY NOBODY DEFINED IS DROPPED, NOT REFUSED. It can only come from a
                         * client this service did not ship, and the fields that matter are
                         * checked for presence afterwards by name -- so a stray entry changes
                         * nothing, while valueOf would turn it into a 500.
                         */
                        try {
                            parsed.put(CollateralFieldKey.valueOf(key), value);
                        } catch (IllegalArgumentException unknownField) {
                            // Ignored deliberately; see above.
                        }
                    });

            return new CollateralDeclaration(documentId(), description(), parsed);
        }
    }

    record ServiceRequestView(
            String id,
            String reference,
            String requestType,
            String details,
            String status,
            String submittedAt,
            String collectionPoint,
            String declineReason) {}

    /** An amount the customer is thinking of paying, or has decided to. */
    record RepaymentRequest(@NotNull @Valid MoneyIn amount, UUID fromAccountId) {}

    /**
     * What a payment would do, as the confirmation screen shows it.
     *
     * <p>EVERY FIGURE COMES FROM THE SERVER. The split between interest and capital depends on
     * what is outstanding at this moment; a browser that worked it out could show a number
     * the ledger then disagrees with.
     */
    record RepaymentPreview(
            MoneyView amount,
            MoneyView interest,
            MoneyView capital,
            MoneyView outstandingBefore,
            MoneyView outstandingAfter,
            MoneyView settlementFigure,
            boolean settlesLoan) {}

    /**
     * The three figures a repayment screen needs before the customer types anything.
     *
     * <p>THE SETTLEMENT FIGURE IS THE BANK'S. It is the outstanding capital plus the interest
     * owed on it, and the page would otherwise have to either invent it or send a speculative
     * payment to find out what it is.
     */
    record SettlementView(
            MoneyView outstanding,
            MoneyView instalment,
            MoneyView settlementFigure,
            String currency) {}

    record RepaymentView(
            String id,
            String loanId,
            MoneyView amount,
            MoneyView interest,
            MoneyView capital,
            MoneyView outstandingAfter,
            String paidAt) {}

    /**
     * One published product, including whether it has to be secured.
     *
     * <p>THE SENTENCE IS COMPOSED HERE, not in the browser. The alternative is shipping the
     * rule and the threshold to the client and having it decide what to say, which means the
     * client comparing an amount against a figure -- client-side arithmetic about what a
     * customer is entitled to, which this project does not do. The server knows the rule, so
     * the server writes the sentence and the screen displays it.
     */
    record ProductView(
            String code,
            String name,
            String interestRatePercent,
            int minMonths,
            int maxMonths,
            String collateralRule,
            String collateralNote) {}

    /** What the bank publishes, so the application form has no second copy of the list. */
    @GetMapping("/products")
    @PreAuthorize("hasRole('CUSTOMER')")
    List<ProductView> products() {
        List<LoanProperties.Product> published =
                products.products() == null ? List.of() : products.products();

        return published.stream()
                .map(
                        product ->
                                new ProductView(
                                        product.code(),
                                        product.name(),
                                        product.annualRatePercent().toPlainString(),
                                        product.minMonths(),
                                        product.maxMonths(),
                                        product.collateral().name(),
                                        noteFor(product)))
                .toList();
    }

    private static String noteFor(LoanProperties.Product product) {
        return switch (product.collateral()) {
            case NEVER -> "No security is needed for this loan.";
            case ALWAYS ->
                    "This loan has to be secured. Attach the document for something you are"
                            + " putting up.";
            case ABOVE_THRESHOLD ->
                    "Security is needed for more than RWF "
                            + grouped(product.collateralRequiredAboveRwf())
                            + ".";
        };
    }

    /**
     * A figure with thousand separators, because this sentence is read beside formatted money.
     *
     * <p>THE SERVER FORMATS IT BECAUSE THE SERVER WROTE THE SENTENCE. Composing the rule here
     * and leaving the browser to interpolate a number into it would put half the sentence in
     * each place; handing over a bare 7250000 to sit under a dropdown reading RWF 650,403 is
     * the visible half of the same mistake.
     *
     * <p>Locale.UK for grouping, matching every other figure this service renders -- see the
     * statement documents, which use it for the same reason.
     */
    private static String grouped(BigDecimal amount) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(amount);
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<LoanView> mine() {
        return loans.forCustomer(AuthController.currentCustomerId()).stream()
                .map(LoansController::view)
                .toList();
    }

    /**
     * What a loan would cost.
     *
     * <p>A SERVER CALL RATHER THAN CLIENT ARITHMETIC, which the client insists on too. The
     * amount is parsed strictly against its currency, so a value with more precision than
     * the currency has is refused instead of rounded into the answer.
     */
    @PostMapping("/simulate")
    @PreAuthorize("hasRole('CUSTOMER')")
    SimulationView simulate(@Valid @RequestBody SimulationRequest request) {
        var result = loans.simulate(request.amount().toMoney(), request.months(), request.product());

        return new SimulationView(
                money(result.principal()),
                result.months(),
                result.product(),
                result.interestRatePercent().toPlainString(),
                money(result.monthlyInstallment()),
                money(result.totalInterest()),
                money(result.totalRepayable()));
    }

    /**
     * Applies for one.
     *
     * <p>A SERVICE REQUEST, not a loan. Nothing is granted, no figure is promised, and the
     * customer gets a reference to quote — the same shape as asking for a card. A loan row
     * appears only when a member of staff records one the bank has actually granted.
     */
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    ServiceRequestView apply(@Valid @RequestBody ApplicationRequest request) {
        ServiceRequestEntity raised =
                serviceRequests.requestLoan(
                        AuthController.currentCustomerId(),
                        request.product(),
                        request.amount().toMoney(),
                        request.months(),
                        request.purpose(),
                        request.disburseToAccountId(),
                        request.collateral() == null
                                ? List.of()
                                : request.collateral().stream()
                                        .map(CollateralIn::toDeclaration)
                                        .toList());

        return new ServiceRequestView(
                raised.id().toString(),
                raised.reference(),
                raised.requestType().name(),
                raised.details(),
                raised.status().name(),
                raised.submittedAt().toString(),
                raised.collectionPoint(),
                raised.declineReason());
    }

    /** What is owed, what the instalment is, and what would clear the loan today. */
    @GetMapping("/{loanId}/settlement")
    @PreAuthorize("hasRole('CUSTOMER')")
    SettlementView settlement(@PathVariable UUID loanId) {
        LoanEntity loan = loans.oneFor(AuthController.currentCustomerId(), loanId);

        return new SettlementView(
                money(loan.outstanding()),
                money(loan.installment()),
                money(LoanService.settlementFigure(loan)),
                loan.currency());
    }

    /** One loan's payments, oldest first: the history half of its statement. */
    @GetMapping("/{loanId}/repayments")
    @PreAuthorize("hasRole('CUSTOMER')")
    List<RepaymentView> repayments(@PathVariable UUID loanId) {
        return loans.historyFor(AuthController.currentCustomerId(), loanId).stream()
                .map(LoansController::repaymentView)
                .toList();
    }

    /**
     * What paying this amount would come to, without paying it.
     *
     * <p>A POST BECAUSE IT CARRIES AN AMOUNT, not because it changes anything -- it changes
     * nothing. A figure a customer is about to pay does not belong in a URL, where it is
     * written to every access log the request passes through.
     */
    @PostMapping("/{loanId}/repayments/preview")
    @PreAuthorize("hasRole('CUSTOMER')")
    RepaymentPreview previewRepayment(
            @PathVariable UUID loanId, @Valid @RequestBody RepaymentRequest request) {

        LoanService.Preview preview =
                loans.preview(
                        AuthController.currentCustomerId(), loanId, request.amount().toMoney());

        return new RepaymentPreview(
                money(preview.amount()),
                money(preview.interest()),
                money(preview.capital()),
                money(preview.outstandingBefore()),
                money(preview.outstandingAfter()),
                money(preview.settlementFigure()),
                preview.settlesLoan());
    }

    /**
     * Pays it.
     *
     * <p>THE KEY IS THE CLIENT'S AND IS REQUIRED. A repayment that times out on the way back
     * looks exactly like one that never arrived, and the app will retry; without a key the
     * retry is a second payment out of the customer's account. Bounded at 80 characters
     * because that is the width of the ledger column it is stored in.
     */
    @PostMapping("/{loanId}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    RepaymentView repay(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String idempotencyKey,
            @PathVariable UUID loanId,
            @Valid @RequestBody RepaymentRequest request) {

        if (request.fromAccountId() == null) {
            throw new BusinessRuleException(
                    "Choose the account the money comes from.");
        }

        return repaymentView(
                loans.repay(
                        AuthController.currentCustomerId(),
                        loanId,
                        request.fromAccountId(),
                        request.amount().toMoney(),
                        idempotencyKey));
    }

    private static MoneyView money(Money amount) {
        return new MoneyView(amount.toPlainString(), amount.currency());
    }

    /*
     * NOT AN OVERLOAD OF view(). Both would be referenced as LoansController::view in a
     * stream, and an overloaded method reference is not pertinent to applicability, so the
     * compiler cannot infer the stream's element type from it. A distinct name costs nothing.
     */
    private static RepaymentView repaymentView(LoanRepaymentEntity repayment) {
        return new RepaymentView(
                repayment.id().toString(),
                repayment.loanId().toString(),
                money(repayment.amount()),
                money(repayment.interest()),
                money(repayment.capital()),
                money(repayment.outstandingAfter()),
                repayment.paidAt().toString());
    }

    private static LoanView view(LoanEntity loan) {
        return new LoanView(
                loan.id().toString(),
                loan.product(),
                loan.reference(),
                loan.currency(),
                money(loan.principal()),
                money(loan.outstanding()),
                money(loan.installment()),
                loan.nextDueDate().toString(),
                loan.interestRatePercent().toPlainString(),
                loan.status().name(),
                loan.customerId().toString());
    }
}
