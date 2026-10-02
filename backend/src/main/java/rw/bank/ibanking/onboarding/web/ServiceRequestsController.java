package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.ServiceRequestEntity;
import rw.bank.ibanking.onboarding.service.ServiceRequestService;

/**
 * The signed-in customer asking the bank for a card or a cheque book, and checking on one.
 *
 * <p>SCOPED TO THE CALLER, always. The customer id comes from the authenticated principal
 * and never from the request, and the account named in a request is checked with the same
 * {@code AccountAccess.mayAct} the transfer path uses.
 *
 * <p>NO RESPONSE HERE STATES A COLLECTION POINT OR A LEAD TIME until a member of staff has
 * typed one. That is why {@link ServiceRequestView} can carry a null {@code
 * collectionPoint}: before the bank has produced the thing, there is nothing true to say
 * about where it will be. The screens that used to name a branch from a list the front end
 * invented now say the bank will be in touch, because that is all anybody knows.
 */
@RestController
@RequestMapping("/api/v1/service-requests")
class ServiceRequestsController {

    private final ServiceRequestService service;

    ServiceRequestsController(ServiceRequestService service) {
        this.service = service;
    }

    /* ------------------------------------------------------------ requests */

    /**
     * ONE SHAPE FOR BOTH KINDS, discriminated by {@code requestType}.
     *
     * <p>{@code cardType} applies to a CARD and {@code leaves} to a CHEQUE_BOOK, so
     * neither can be required at the field level. The service refuses the wrong
     * combination with a sentence naming what is missing, which is a better error than a
     * field-level one that fires on whichever field the other kind left empty.
     *
     * <p>THERE IS NO {@code branch} FIELD, and its absence is the point. The old request
     * carried one, chosen by the customer from six names the portal had made up.
     */
    record NewServiceRequest(
            @NotBlank(message = "Say whether this is a card or a cheque book.") @Size(max = 16)
                    String requestType,
            @NotBlank(message = "Choose which account this is for.") @Size(max = 64)
                    String accountId,
            @Size(max = 16) String cardType,
            Integer leaves) {}

    /* ------------------------------------------------------------ responses */

    /**
     * A request as the customer sees it.
     *
     * <p>IT CARRIES THE DECLINE REASON. A request that simply reads "declined" sends the
     * customer to a telephone; the reason is often something they can put right
     * themselves, and they were emailed it, so a screen that stayed silent would disagree
     * with their inbox.
     *
     * <p>{@code collectionPoint} is absent until staff name it. The service sets
     * {@code default-property-inclusion: non_null}, so an absent field is left out of the
     * JSON rather than sent as null — which is why the client's type for it is optional
     * rather than nullable.
     */
    record ServiceRequestView(
            String id,
            String reference,
            String requestType,
            String details,
            String status,
            String submittedAt,
            String collectionPoint,
            String declineReason) {

        static ServiceRequestView of(ServiceRequestEntity request) {
            return new ServiceRequestView(
                    request.id().toString(),
                    request.reference(),
                    request.requestType().name(),
                    request.details(),
                    request.status().name(),
                    request.submittedAt().toString(),
                    request.collectionPoint(),
                    request.declineReason());
        }
    }

    /* ------------------------------------------------------------ endpoints */

    /**
     * This customer's requests, newest first.
     *
     * <p>{@code hasRole("CUSTOMER")} rather than {@code authenticated()}: a session holding
     * MUST_CHANGE_PASSWORD is authenticated and must reach nothing but the change-password
     * screen.
     */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    List<ServiceRequestView> mine() {
        return service.forCustomer(AuthController.currentCustomerId()).stream()
                .map(ServiceRequestView::of)
                .toList();
    }

    /** Asks for a card or a cheque book. Nothing is produced and nothing is promised. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    ServiceRequestView raise(@Valid @RequestBody NewServiceRequest request) {
        UUID customerId = AuthController.currentCustomerId();
        UUID accountId = parseAccountId(request.accountId());

        return ServiceRequestView.of(
                switch (request.requestType().trim().toUpperCase(java.util.Locale.ROOT)) {
                    case "CARD" ->
                            service.requestCard(
                                    customerId,
                                    request.cardType() == null ? "DEBIT" : request.cardType(),
                                    accountId);
                    case "CHEQUE_BOOK" ->
                            service.requestChequeBook(
                                    customerId,
                                    request.leaves() == null ? 0 : request.leaves(),
                                    accountId);
                    default ->
                            throw new rw.bank.ibanking.exception.BusinessRuleException(
                                    "Choose a card or a cheque book.");
                });
    }

    /** A malformed account id is "we could not find it", not a deserialisation failure. */
    private static UUID parseAccountId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException malformed) {
            throw new ResourceNotFoundException("We could not find that account on your profile.");
        }
    }
}
