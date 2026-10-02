package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.onboarding.domain.BeneficiaryEntity;
import rw.bank.ibanking.onboarding.domain.BeneficiaryType;
import rw.bank.ibanking.onboarding.service.BeneficiaryService;

/**
 * The signed-in customer's own saved payees.
 *
 * <p>SCOPED TO THE CALLER, always. The customer id comes from the authenticated principal
 * and never from the request — an endpoint that accepted one would let anybody signed in
 * read, add to, or delete from somebody else's payee list by changing an id.
 *
 * <p>NO ACCOUNT NUMBER IS EVER RETURNED. Every view below carries the mask the row was
 * saved with. The full number is the payee's payment address and the service is the only
 * thing that reads it, when a transfer is being made; see {@code
 * BeneficiaryEntity.destinationForPayment()}, which is named the way it is so that a
 * response-building method never reaches for it.
 *
 * <p>A NEW PAYEE IS NOT PAYABLE. It is saved {@code PENDING_VERIFICATION} and a member of
 * bank staff compares the name the customer typed with the name the bank holds for that
 * account before anything can be sent to it. V17 records why that is a person rather than
 * a timer, and {@code BeneficiaryService} is where the gate is actually applied.
 */
@RestController
@RequestMapping("/api/v1/beneficiaries")
class BeneficiariesController {

    private final BeneficiaryService service;

    BeneficiariesController(BeneficiaryService service) {
        this.service = service;
    }

    /* ------------------------------------------------------------ requests */

    record NewBeneficiaryRequest(
            @NotBlank(message = "Give the payee a name, so you can recognise them.")
                    @Size(max = 160)
                    String name,
            @NotBlank(message = "Choose what kind of payee this is.") @Size(max = 16)
                    String beneficiaryType,
            @Size(max = 120) String provider,
            @NotBlank(message = "Enter the full account or mobile number you want to pay.")
                    @Size(max = 34)
                    String destination,
            @Size(max = 3) String currency) {}

    /* ------------------------------------------------------------ responses */

    /**
     * A payee as the customer sees it.
     *
     * <p>IT CARRIES THE REFUSAL REASON. A payee that simply reads "refused" sends the
     * customer to a telephone; the commonest reason — the name does not match the account
     * the bank holds — is one they fix themselves in a minute by checking the number they
     * typed. They were emailed it too, and a screen that disagrees with the email would be
     * worse than either alone.
     *
     * <p>IT DOES NOT CARRY THE NAME THE BANK HOLDS. That name belongs to the payee, not to
     * the customer asking about them: returning it here would turn every customer's own
     * payee list into the account-holder lookup that {@code /transfers/resolve} is rate
     * limited precisely to bound. The reviewer sees it; the customer is told whether it
     * matched.
     */
    record BeneficiaryView(
            String id,
            String name,
            String beneficiaryType,
            String provider,
            String maskedDestination,
            String currency,
            String status,
            String addedAt,
            String refusedReason) {

        static BeneficiaryView of(BeneficiaryEntity payee) {
            return new BeneficiaryView(
                    payee.id().toString(),
                    payee.name(),
                    payee.beneficiaryType().name(),
                    payee.provider(),
                    payee.maskedDestination(),
                    payee.currency(),
                    payee.status().name(),
                    payee.addedAt().toString(),
                    payee.refusedReason());
        }
    }

    /* ------------------------------------------------------------ endpoints */

    /**
     * This customer's payees, newest first.
     *
     * <p>{@code hasRole("CUSTOMER")} rather than {@code authenticated()}: a session holding
     * MUST_CHANGE_PASSWORD is authenticated, and it must not reach anything but the
     * change-password screen.
     */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<BeneficiaryView> mine() {
        return service.forCustomer(AuthController.currentCustomerId()).stream()
                .map(BeneficiaryView::of)
                .toList();
    }

    /** Saves a payee. It cannot be paid until staff have checked it. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    BeneficiaryView add(@Valid @RequestBody NewBeneficiaryRequest request) {
        return BeneficiaryView.of(
                service.add(
                        AuthController.currentCustomerId(),
                        new BeneficiaryService.NewBeneficiary(
                                request.name(),
                                typeOf(request.beneficiaryType()),
                                request.provider(),
                                request.destination(),
                                request.currency())));
    }

    /**
     * Forgets a payee.
     *
     * <p>Allowed whatever its status, including while it is waiting. A customer who
     * realises they typed the wrong number should not have to wait for staff to refuse it
     * before they can remove it.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('CUSTOMER')")
    void remove(@PathVariable UUID id) {
        service.remove(AuthController.currentCustomerId(), id);
    }

    /**
     * An unknown type is a sentence rather than a deserialisation failure.
     *
     * <p>Parsed here instead of typed as the enum on the record so that a client sending
     * something stale gets a message naming the choice, not a 400 whose body is about
     * JSON.
     */
    private static BeneficiaryType typeOf(String raw) {
        try {
            return BeneficiaryType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessRuleException(
                    "Choose whether this payee is at this bank, another bank in Rwanda, a bank"
                            + " abroad, or a mobile wallet.");
        }
    }
}
