package rw.bank.ibanking.onboarding.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.AccountType;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.ServiceRequestEntity;
import rw.bank.ibanking.onboarding.domain.ServiceRequestStatus;
import rw.bank.ibanking.onboarding.domain.ServiceRequestType;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.ServiceRequestRepository;

/**
 * CARDS AND CHEQUE BOOKS, FROM THE CUSTOMER ASKING TO SOMEBODY COLLECTING ONE.
 *
 * <p>WHAT THIS REPLACED. Both screens posted to endpoints only the browser's own mock
 * answered, and that mock pushed a row onto an in-memory array which starts empty on every
 * page load. The request reached nobody, no staff screen existed to show it, and the
 * reference the customer was told stopped existing when they refreshed the page.
 *
 * <p>AND THE SCREEN MADE THREE PROMISES, NONE OF WHICH WAS THE BANK'S. A reference number
 * minted from {@code Date.now()}; "about five working days to print", a constant in a React
 * component; and a named branch to collect from, drawn from a six-item list the front end
 * had invented. docs/OPEN-ITEMS.md already recorded that no branch list had ever been
 * supplied. So the portal sent people to branches it made up.
 *
 * <p>WHICH IS WHY THE CUSTOMER NO LONGER PICKS A COLLECTION POINT. {@link #markReady}
 * requires a member of staff to type one, and until they do, nothing in the product states
 * where to collect anything or how long it will take. That is the difference between a
 * queue that works and a queue that works while still lying to people.
 *
 * <p>EITHER STAFF ROLE MAY ACT, on the same reasoning as the payee queue: the maker is the
 * CUSTOMER and bank staff are the checker, so the four-eyes rule that keeps ADMIN and
 * MANAGER apart for account creation is not in play.
 */
@Service
public class ServiceRequestService {

    private static final Logger log = LoggerFactory.getLogger(ServiceRequestService.class);

    /** Everything a member of staff still has to do something about. */
    private static final List<ServiceRequestStatus> OPEN =
            List.of(ServiceRequestStatus.SUBMITTED, ServiceRequestStatus.READY);

    /** How many open requests one customer may have, across both kinds. */
    private static final int MAX_OPEN_PER_CUSTOMER = 6;

    /** Cheque leaves the bank will print. Anything else is refused rather than rounded. */
    private static final List<Integer> ALLOWED_LEAVES = List.of(25, 50, 100);

    private final ServiceRequestRepository requests;
    private final CustomerRepository customers;
    private final CustomerAccountRepository accounts;
    private final AccountAccess access;
    private final Mailer mailer;
    private final Notifications notifications;

    ServiceRequestService(
            ServiceRequestRepository requests,
            CustomerRepository customers,
            CustomerAccountRepository accounts,
            AccountAccess access,
            Mailer mailer,
            Notifications notifications) {
        this.requests = requests;
        this.customers = customers;
        this.accounts = accounts;
        this.access = access;
        this.mailer = mailer;
        this.notifications = notifications;
    }

    /* ==================================================== the customer's side */

    /** One customer's own requests, newest first. */
    @Transactional(readOnly = true)
    public List<ServiceRequestEntity> forCustomer(UUID customerId) {
        return requests.findByCustomerIdOrderBySubmittedAtDesc(customerId);
    }

    /**
     * Asks for a card on one of the customer's accounts.
     *
     * @param cardType DEBIT or PREPAID. Validated against a list rather than written
     *     through, because this string ends up on a printing instruction.
     */
    @Transactional
    public ServiceRequestEntity requestCard(UUID customerId, String cardType, UUID accountId) {
        String kind = normalise(cardType);
        if (!kind.equals("DEBIT") && !kind.equals("PREPAID")) {
            throw new BusinessRuleException("Choose a debit card or a prepaid card.");
        }

        CustomerAccountEntity account = requireOwnAccount(customerId, accountId);

        return raise(
                customerId,
                ServiceRequestType.CARD,
                account,
                (kind.equals("DEBIT") ? "Debit" : "Prepaid") + " card for " + account.maskedNumber());
    }

    /**
     * Asks for a cheque book.
     *
     * <p>CURRENT ACCOUNTS ONLY, checked here as well as on the screen. The form narrows its
     * own list, and a rule enforced only in a dropdown is not a rule — the request can be
     * built by anything that can make an HTTP call.
     */
    @Transactional
    public ServiceRequestEntity requestChequeBook(UUID customerId, int leaves, UUID accountId) {
        if (!ALLOWED_LEAVES.contains(leaves)) {
            throw new BusinessRuleException("Cheque books come with 25, 50 or 100 leaves.");
        }

        CustomerAccountEntity account = requireOwnAccount(customerId, accountId);
        if (account.accountType() != AccountType.CURRENT) {
            throw new BusinessRuleException(
                    "Cheque books are only issued on a current account. Choose a current"
                            + " account, or speak to the bank about opening one.");
        }

        return raise(
                customerId,
                ServiceRequestType.CHEQUE_BOOK,
                account,
                "Cheque book, " + leaves + " leaves, for " + account.maskedNumber());
    }

    /**
     * The common path: dedupe, cap, save.
     *
     * <p>NOTHING HERE PROMISES A TIME OR A PLACE. The row is created with no collection
     * point and the response carries none, so there is nothing for a screen to render as a
     * branch name. That is enforced by the absence of the data rather than by everyone
     * remembering not to write the sentence.
     */
    private ServiceRequestEntity raise(
            UUID customerId, ServiceRequestType type, CustomerAccountEntity account, String details) {

        CustomerEntity customer = requireCustomer(customerId);

        List<ServiceRequestEntity> existing =
                requests.findByCustomerIdAndRequestTypeAndAccountIdAndStatusIn(
                        customerId, type, account.id(), OPEN);

        if (!existing.isEmpty()) {
            /*
             * NAMES THE EXISTING REFERENCE rather than just refusing. Somebody pressing
             * the button twice usually did so because they could not see the first
             * request; telling them its reference answers that, and stops the bank
             * printing two cards.
             */
            ServiceRequestEntity open = existing.get(0);
            throw new BusinessRuleException(
                    "You have already asked for a "
                            + type.label()
                            + " on that account. The reference is "
                            + open.reference()
                            + " and it is still with us.");
        }

        if (requests.countByCustomerIdAndStatusIn(customerId, OPEN) >= MAX_OPEN_PER_CUSTOMER) {
            throw new BusinessRuleException(
                    "You have "
                            + MAX_OPEN_PER_CUSTOMER
                            + " requests with us already. Once some of those are done you can"
                            + " ask for more.");
        }

        ServiceRequestEntity saved =
                requests.save(
                        ServiceRequestEntity.raisedBy(customerId, type, account.id(), details));

        log.info(
                "Customer {} asked for a {} ({}), reference {}",
                customer.customerNumber(),
                type.label(),
                account.maskedNumber(),
                saved.reference());

        return saved;
    }

    /* ======================================================= the staff's side */

    /** A request, with the customer it belongs to, for the queue screen. */
    public record Queued(ServiceRequestEntity request, CustomerEntity customer, String accountMask) {}

    /** Everything still open, oldest first. */
    @Transactional(readOnly = true)
    public List<Queued> open() {
        return requests.findByStatusInOrderBySubmittedAtAsc(OPEN).stream().map(this::queued).toList();
    }

    @Transactional(readOnly = true)
    public Queued queued(ServiceRequestEntity request) {
        return new Queued(
                request,
                requireCustomer(request.customerId()),
                accounts
                        .findById(request.accountId())
                        .map(CustomerAccountEntity::maskedNumber)
                        .orElse("****"));
    }

    /**
     * Marks it produced, records where it is, and tells the customer.
     *
     * <p>The collection point is the only thing in this flow the bank has not already told
     * the portal, and it is required — see {@link ServiceRequestEntity#markedReady}. The
     * email is sent in the same transaction as the status change: a request marked ready
     * with no email is a customer waiting for a message that never comes, and an email
     * with no row behind it sends somebody to a counter where nobody is expecting them.
     */
    @Transactional
    public ServiceRequestEntity markReady(
            UUID requestId, String collectionPoint, StaffEntity staff) {

        ServiceRequestEntity request = find(requestId);
        CustomerEntity customer = requireCustomer(request.customerId());

        request.markedReady(staff.fullName(), collectionPoint);
        requests.save(request);

        mailer.send(
                OutboxKind.SERVICE_REQUEST_READY,
                customer.email(),
                EmailTemplates.SERVICE_REQUEST_READY_SUBJECT,
                EmailTemplates.serviceRequestReady(
                        customer.fullName(),
                        request.requestType().label(),
                        request.reference(),
                        request.collectionPoint()));

        /*
         * AND IN THE PORTAL, in the same transaction as the email. The customer who found
         * this gap had exactly this happen: the card was made, the collection point was
         * typed, the email arrived — and the dashboard still read "Nothing new." The email
         * reaches an inbox; this reaches somebody who signs in without reading it.
         *
         * The link points at the list for THIS kind, because a customer who asked for a
         * cheque book should not be sent to the cards page.
         */
        notifications.serviceRequestReady(
                customer.id(),
                request.requestType().label(),
                request.reference(),
                request.collectionPoint(),
                listPathFor(request.requestType()));

        log.info(
                "Request {} for customer {} is ready at {}, marked by {}",
                request.reference(),
                customer.customerNumber(),
                request.collectionPoint(),
                staff.email());

        return request;
    }

    /**
     * Marks it handed over.
     *
     * <p>NO EMAIL. The customer is standing at the counter — a message telling them they
     * have just been given their card is noise, and the same reasoning is recorded against
     * the absence of a "request submitted" email.
     */
    @Transactional
    public ServiceRequestEntity markCollected(UUID requestId, StaffEntity staff) {
        ServiceRequestEntity request = find(requestId);
        request.markedCollected(staff.fullName());
        requests.save(request);

        log.info("Request {} was collected, recorded by {}", request.reference(), staff.email());
        return request;
    }

    /** Declines it, with the reason the customer is shown and emailed. */
    @Transactional
    public ServiceRequestEntity decline(UUID requestId, String reason, StaffEntity staff) {
        ServiceRequestEntity request = find(requestId);
        CustomerEntity customer = requireCustomer(request.customerId());

        // Throws if the reason is missing, or if somebody else got here first.
        request.declined(staff.fullName(), reason);
        requests.save(request);

        mailer.send(
                OutboxKind.SERVICE_REQUEST_DECLINED,
                customer.email(),
                EmailTemplates.SERVICE_REQUEST_DECLINED_SUBJECT,
                EmailTemplates.serviceRequestDeclined(
                        customer.fullName(),
                        request.requestType().label(),
                        request.reference(),
                        request.declineReason()));

        notifications.serviceRequestDeclined(
                customer.id(),
                request.requestType().label(),
                request.reference(),
                request.declineReason(),
                listPathFor(request.requestType()));

        log.info(
                "Request {} for customer {} was declined by {}",
                request.reference(),
                customer.customerNumber(),
                staff.email());

        return request;
    }

    /* ============================================================== plumbing */

    /**
     * Where a notification about this kind of request should send the customer.
     *
     * <p>HARD-CODED PATHS, and they are the portal's own routes rather than anything the
     * client sent — V20 refuses to store a link that is not a path, and this is the only
     * code that supplies one for these two kinds. If a route is renamed, this is the single
     * place that follows it.
     */
    private static String listPathFor(ServiceRequestType type) {
        return type == ServiceRequestType.CARD ? "/cards" : "/cheque-books";
    }

    private ServiceRequestEntity find(UUID requestId) {
        return requests
                .findById(requestId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find that request. It may already have"
                                                + " been dealt with."));
    }

    /**
     * The account, if this customer may act on it.
     *
     * <p>Through {@code AccountAccess.mayAct}, the same check the transfer path uses, so a
     * request cannot name somebody else's account by changing an id — and a company account
     * the customer administers is handled by the same rule rather than by a second one.
     */
    private CustomerAccountEntity requireOwnAccount(UUID customerId, UUID accountId) {
        if (accountId == null) {
            throw new BusinessRuleException("Choose which account this is for.");
        }
        return accounts
                .findById(accountId)
                .filter(found -> access.mayAct(customerId, found))
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find that account on your profile."));
    }

    private CustomerEntity requireCustomer(UUID customerId) {
        return customers
                .findById(customerId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find the customer this request belongs"
                                                + " to."));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
