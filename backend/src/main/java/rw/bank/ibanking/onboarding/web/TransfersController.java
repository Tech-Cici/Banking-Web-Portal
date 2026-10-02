package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.TransferEntity;
import rw.bank.ibanking.onboarding.service.BeneficiaryService;
import rw.bank.ibanking.onboarding.service.TransferService;

/**
 * The customer's side of a transfer: check who you are paying, send it, watch it.
 *
 * <p>NOTHING HERE DECIDES ANYTHING. Every rule — entitlement to the source account, the
 * currency match, whether the money is there — is {@code TransferService}'s, and this
 * class only carries values across the wire. A control on this side would be a control a
 * different client could skip.
 *
 * <p>NO ACCOUNT NUMBER IS EVER RETURNED. The customer supplies the beneficiary's full
 * number because that is the only thing that identifies an account; every response speaks
 * in masks and ids.
 */
@RestController
@RequestMapping("/api/v1/transfers")
class TransfersController {

    private final TransferService service;

    /** Only to turn a saved payee into a destination — see {@code destinationNumberFor}. */
    private final BeneficiaryService beneficiaries;

    TransfersController(TransferService service, BeneficiaryService beneficiaries) {
        this.service = service;
        this.beneficiaries = beneficiaries;
    }

    /* ------------------------------------------------------------ requests */

    record ResolveRequest(@NotBlank @Size(max = 34) String accountNumber) {}

    /**
     * EXACTLY ONE OF {@code destinationAccountNumber}, {@code destinationAccountId} OR
     * {@code beneficiaryId}.
     *
     * <p>None carries {@code @NotBlank}, because the requirement is "exactly one of
     * these" and no single-field annotation can say that. The refusal names the choice,
     * which is a better error than a field-level one that fires on whichever field
     * happened to be left empty.
     *
     * <p>The three are the three ways a customer names a destination. A NUMBER is one they
     * typed. An ACCOUNT ID is one of their OWN accounts, picked from a list — the client
     * never receives a full account number, so it has nothing else to send. A BENEFICIARY
     * ID is a payee they saved earlier, and it is the ONLY way to pay one: the payee's
     * number is never sent to the browser, so the browser cannot put it in this request
     * even if it wanted to, and the server reads it only after checking that the payee is
     * this customer's and that bank staff have approved it.
     */
    record SubmitRequest(
            @NotBlank String sourceAccountId,
            @Size(max = 34) String destinationAccountNumber,
            @Size(max = 64) String destinationAccountId,
            @Size(max = 64) String beneficiaryId,
            /*
             * A STRING, and it stays one all the way to the database. A JSON number is a
             * double by the time Jackson has finished with it, and a double cannot hold
             * 1234.30 — it becomes 1234.2999999999999, which is a rounding decision
             * nobody made on somebody's payment.
             */
            @NotBlank @Size(max = 32) String amount,
            @NotBlank @Size(max = 140) String reference) {}

    /* ------------------------------------------------------------ responses */

    /**
     * Who holds an account number.
     *
     * <p>A name, a mask and a currency — never the account id and never the number the
     * caller supplied. Nothing here can be replayed as a destination by anybody who did
     * not already have the number.
     */
    record BeneficiaryView(String holderName, String maskedNumber, String currency) {

        static BeneficiaryView of(TransferService.Beneficiary beneficiary) {
            return new BeneficiaryView(
                    beneficiary.holderName(), beneficiary.maskedNumber(), beneficiary.currency());
        }
    }

    /**
     * A transfer, as the customer's screens read it.
     *
     * <p>{@code status} is the server's word and the client must not improve on it. There
     * is no COMPLETED: a pending transfer is pending, and an approved one has already
     * arrived.
     */
    record TransferView(
            String id,
            String status,
            String amount,
            String currency,
            String reference,
            String submittedAt,
            String decidedAt,
            String rejectionReason) {

        static TransferView of(TransferEntity transfer) {
            return new TransferView(
                    transfer.id().toString(),
                    transfer.status().name(),
                    transfer.amount().toPlainString(),
                    transfer.amount().currency(),
                    transfer.reference(),
                    transfer.submittedAt().toString(),
                    transfer.decidedAt() == null ? null : transfer.decidedAt().toString(),
                    transfer.rejectionReason());
        }
    }

    /* ------------------------------------------------------------ endpoints */

    /**
     * Who holds this account number.
     *
     * <p>A POST rather than a GET even though it changes nothing: the account number would
     * otherwise sit in a URL, and a URL is the one place a value is guaranteed to be
     * written down — browser history, proxy logs, the referrer header of the next request.
     *
     * <p>RATE LIMITED PER CUSTOMER, twenty an hour. Answering this catches the mistake
     * nothing else can — money sent to a stranger — and answered without limit it turns a
     * list of account numbers into a list of names. See {@code BeneficiaryLookups}. Over
     * the limit the customer gets 429 with a Retry-After, not a refusal they cannot read.
     */
    @PostMapping("/resolve")
    @PreAuthorize("hasRole('CUSTOMER')")
    BeneficiaryView resolve(@Valid @RequestBody ResolveRequest request) {
        return BeneficiaryView.of(
                service.resolve(AuthController.currentCustomerId(), request.accountNumber()));
    }

    /**
     * Submits a transfer. The money leaves the sender now and waits for a manager.
     *
     * @param idempotencyKey required, and the reason is a lost response rather than a
     *     careless client: the customer taps Send, the money is held, the reply never
     *     arrives, and the app retries. Without the key that is a second transfer sitting
     *     in a manager's queue looking as legitimate as the first.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    TransferView submit(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody SubmitRequest request) {

        UUID customerId = AuthController.currentCustomerId();

        return TransferView.of(
                service.submit(
                        customerId,
                        new TransferService.Instruction(
                                UUID.fromString(request.sourceAccountId()),
                                destinationNumberFor(customerId, request),
                                /*
                                 * Parsed here rather than typed as a UUID on the record,
                                 * so a malformed id is a 400 with a sentence about the
                                 * account rather than a Jackson deserialisation failure
                                 * the customer cannot act on.
                                 */
                                parseAccountId(request.destinationAccountId()),
                                request.amount(),
                                request.reference(),
                                idempotencyKey)));
    }

    /**
     * TURNS A SAVED PAYEE INTO A DESTINATION, AND IS WHERE THE PAYEE GATE IS APPLIED.
     *
     * <p>WHY THE TRANSLATION IS HERE RATHER THAN IN {@code TransferService}. The transfer
     * service's job is moving money between accounts; it has never known what a saved payee
     * is, and giving it a payee repository would make every transfer path depend on a
     * concept only one of them uses. What arrives at the service is still an account
     * number, exactly as a typed destination is — the payee is a lookup, not a new kind of
     * transfer.
     *
     * <p>THE CHECK ITSELF IS NOT HERE. {@code BeneficiaryService.destinationFor} refuses a
     * payee that is not this customer's and one bank staff have not approved, so the
     * decision lives with the code that owns the payee's lifecycle and cannot be skipped by
     * a future second caller that forgets it. This method only chooses which of the three
     * destination forms the request used.
     *
     * <p>The previous arrangement had no server-side check at all: the status was filtered
     * in the transfer screen's dropdown, and that filter looked only at the payee's type,
     * so an unapproved payee was payable from the one screen that moves money.
     */
    private String destinationNumberFor(UUID customerId, SubmitRequest request) {
        String payeeId = request.beneficiaryId();
        if (payeeId == null || payeeId.isBlank()) return request.destinationAccountNumber();

        /*
         * Refused rather than silently preferred. A request naming a payee AND a number is
         * one the client built wrongly, and quietly honouring one of the two would make a
         * customer's money depend on which branch of this method ran.
         */
        if (request.destinationAccountNumber() != null
                && !request.destinationAccountNumber().isBlank()) {
            throw new rw.bank.ibanking.exception.BusinessRuleException(
                    "Choose a saved payee or type an account number, not both.");
        }
        if (request.destinationAccountId() != null && !request.destinationAccountId().isBlank()) {
            throw new rw.bank.ibanking.exception.BusinessRuleException(
                    "Choose a saved payee or one of your own accounts, not both.");
        }

        return beneficiaries.destinationFor(customerId, parsePayeeId(payeeId));
    }

    /** A malformed payee id is "we could not find it", not a deserialisation failure. */
    private static UUID parsePayeeId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException malformed) {
            throw new rw.bank.ibanking.exception.ResourceNotFoundException(
                    "We could not find that payee on your list.");
        }
    }

    /** Null for an absent id, or a 400 for one that is not a UUID at all. */
    private static UUID parseAccountId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException malformed) {
            throw new rw.bank.ibanking.exception.BusinessRuleException(
                    "We could not find that account.");
        }
    }

    /** Everything this customer's accounts have sent or received. */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<TransferView> mine() {
        return service.forCustomer(AuthController.currentCustomerId()).stream()
                .map(TransferView::of)
                .toList();
    }

    /**
     * What is currently held out of this customer's accounts.
     *
     * <p>The dashboard reads this to explain a balance that has already dropped. Without
     * it the customer sees money gone and nothing saying where.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasRole('CUSTOMER')")
    List<TransferView> pending() {
        return service.pendingFor(AuthController.currentCustomerId()).stream()
                .map(TransferView::of)
                .toList();
    }
}
