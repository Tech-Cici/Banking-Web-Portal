package rw.bank.ibanking.onboarding.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.collateral.CollateralDeclaration;
import rw.bank.ibanking.collateral.CollateralService;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.config.LoanProperties;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.AccountType;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.LoanEntity;
import rw.bank.ibanking.onboarding.domain.LoanStatus;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.ServiceRequestEntity;
import rw.bank.ibanking.onboarding.domain.ServiceRequestStatus;
import rw.bank.ibanking.onboarding.domain.ServiceRequestType;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.repo.CardRepository;
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
    private final CardService cards;
    private final LoanService loans;

    /*
     * THE PUBLISHED PRODUCTS, not the loan service, because what is wanted here is the rule
     * rather than the arithmetic: which products exist and which of them have to be secured.
     */
    private final LoanProperties loanProducts;

    private final CollateralService collateral;

    ServiceRequestService(
            ServiceRequestRepository requests,
            CustomerRepository customers,
            CustomerAccountRepository accounts,
            AccountAccess access,
            Mailer mailer,
            Notifications notifications,
            CardService cards,
            LoanService loans,
            LoanProperties loanProducts,
            CollateralService collateral) {
        this.requests = requests;
        this.customers = customers;
        this.accounts = accounts;
        this.access = access;
        this.mailer = mailer;
        this.notifications = notifications;
        this.cards = cards;
        this.loans = loans;
        this.loanProducts = loanProducts;
        this.collateral = collateral;
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
     * Applies for a loan.
     *
     * <p>NOTHING IS GRANTED HERE AND NO FIGURE IS PROMISED. The amount and the term are
     * what the customer ASKED FOR, recorded in the details so staff can read the request
     * back; whether the bank lends, how much, and at what rate are decisions it has not
     * made yet. A row in `loans` appears only when somebody records one it has granted.
     */
    @Transactional
    public ServiceRequestEntity requestLoan(
            UUID customerId,
            String product,
            Money amount,
            int months,
            String purpose,
            UUID accountId,
            List<CollateralDeclaration> declarations) {

        CustomerAccountEntity account = requireOwnAccount(customerId, accountId);

        if (!account.currency().equals(amount.currency())) {
            throw new BusinessRuleException(
                    "The amount must be in " + account.currency() + ", the account's own currency.");
        }

        if (months <= 0) {
            throw new BusinessRuleException("Choose how many months to repay over.");
        }
        if (amount.minorUnits() <= 0) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }

        CustomerEntity customer = requireCustomer(customerId);
        refuseIfAlreadyOpen(customerId, ServiceRequestType.LOAN_APPLICATION, account);
        refuseIfTooMany(customerId);

        requireSecurityFor(product, amount, declarations);
        refuseIfOverExposed(customerId, amount);

        /*
         * THE SENTENCE AND THE FIGURES ARE BOTH KEPT, and they are not the same thing. The
         * sentence is what a queue screen shows and it carries the customer's own words for
         * the purpose. The figures are what granting the loan reads. Deriving one from the
         * other in either direction is how an amount comes to be decided by a regular
         * expression.
         */
        String normalisedProduct = normalise(product);

        ServiceRequestEntity saved =
                requests.save(
                        ServiceRequestEntity.loanApplied(
                                customerId,
                                account.id(),
                                normalisedProduct.replace('_', ' ').toLowerCase(Locale.ROOT)
                                        + ", "
                                        + amount.currency()
                                        + " "
                                        + amount.toPlainString()
                                        + " over "
                                        + months
                                        + " months, into "
                                        + account.maskedNumber()
                                        + " — "
                                        + purpose.trim(),
                                normalisedProduct,
                                amount,
                                months));

        if (declarations != null && !declarations.isEmpty()) {
            collateral.link(customerId, saved.id(), declarations);
        }

        log.info(
                "Customer {} applied for a loan ({}), reference {}, {} item(s) pledged",
                customer.customerNumber(),
                account.maskedNumber(),
                saved.reference(),
                declarations == null ? 0 : declarations.size());

        return saved;
    }

    /**
     * Refuses an application that has to be secured and is not.
     *
     * <p>HERE, NOT ON THE FORM. The screen knows the rule and says so while the customer is
     * typing, which is a courtesy; this is the rule. A client that has been edited, or is
     * simply out of date, cannot talk the bank into an unsecured loan.
     *
     * <p>AN UNCONFIGURED PRODUCT IS REFUSED. Before this, an application naming a product the
     * bank does not publish was stored and went to a manager; now it stops here. The reason is
     * the collateral rule itself: a product with no configuration has no rule, and the only
     * two things to do with a loan whose security requirement is unknown are to refuse it or
     * to guess. Guessing, in the direction that costs nothing, is how a large loan goes out
     * against nothing at all.
     */
    /**
     * Refuses an application that would take the customer past what the bank will lend them.
     *
     * <p>A SUM, NOT A COUNT. "You may not borrow while you owe us anything" would stop a
     * customer with two hundred francs left on a settled-but-for-one-payment loan from
     * borrowing at all, and would let somebody with nothing outstanding borrow a fortune. What
     * matters is how much would be owed afterwards, so that is what is measured.
     *
     * <p>WHICH IS ALSO WHY A PAID-OFF LOAN STOPS MATTERING WITHOUT A RULE OF ITS OWN. Its
     * outstanding balance is zero; it contributes nothing to the total. There is no special
     * case for SETTLED anywhere in here, and nothing to keep in step with the loan lifecycle.
     *
     * <p>FRANCS ONLY, AND ANYTHING ELSE IS SENT TO A BRANCH. Adding a dollar balance to a
     * franc ceiling needs an exchange rate this portal does not have; inventing one to decide
     * whether somebody may borrow would be the worst possible place to guess. The form offers
     * francs alone, so this is a guard rather than a path anybody walks.
     */
    private void refuseIfOverExposed(UUID customerId, Money amount) {
        BigDecimal ceiling = loanProducts.maxTotalExposureRwf();
        if (ceiling == null || ceiling.signum() <= 0) return;

        List<LoanEntity> running =
                loans.forCustomer(customerId).stream()
                        .filter(loan -> loan.status() != LoanStatus.SETTLED)
                        .toList();

        if (running.isEmpty()) return;

        boolean allFrancs =
                "RWF".equals(amount.currency())
                        && running.stream().allMatch(loan -> "RWF".equals(loan.currency()));

        if (!allFrancs) {
            throw new BusinessRuleException(
                    "You have a loan in another currency, so this application cannot be"
                            + " assessed online. Please speak to the bank.");
        }

        Money owed = Money.parse("0", "RWF");
        for (LoanEntity loan : running) {
            owed = owed.plus(loan.outstanding());
        }

        Money afterwards = owed.plus(amount);
        Money most = Money.parse(ceiling.toPlainString(), "RWF");

        if (!afterwards.isGreaterThan(most)) return;

        throw new BusinessRuleException(
                "You already owe RWF "
                        + owed.toPlainString()
                        + " on "
                        + (running.size() == 1 ? "a loan" : running.size() + " loans")
                        + ". With this one that would be RWF "
                        + afterwards.toPlainString()
                        + ", and the bank lends up to RWF "
                        + most.toPlainString()
                        + " in total. Pay some of it down, or ask for less.");
    }

    private void requireSecurityFor(
            String product, Money amount, List<CollateralDeclaration> declarations) {

        LoanProperties.Product configured = loanProducts.require(product);

        if (configured == null) {
            throw new BusinessRuleException("The bank does not offer that loan at the moment.");
        }

        boolean pledged = declarations != null && !declarations.isEmpty();
        if (pledged || !configured.requiresCollateral(amount)) return;

        throw new BusinessRuleException(
                "A "
                        + configured.name().toLowerCase(Locale.ROOT)
                        + " of this amount has to be secured. Attach the document for"
                        + " something you are putting up: land, a house, a vehicle or a"
                        + " business.");
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

        refuseIfAlreadyOpen(customerId, type, account);
        refuseIfTooMany(customerId);

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

    /**
     * One open request of a kind per account.
     *
     * <p>NAMES THE EXISTING REFERENCE rather than just refusing. Somebody pressing the button
     * twice usually did so because they could not see the first request; telling them its
     * reference answers that, and stops the bank printing two cards.
     */
    private void refuseIfAlreadyOpen(
            UUID customerId, ServiceRequestType type, CustomerAccountEntity account) {

        List<ServiceRequestEntity> existing =
                requests.findByCustomerIdAndRequestTypeAndAccountIdAndStatusIn(
                        customerId, type, account.id(), OPEN);

        if (existing.isEmpty()) return;

        ServiceRequestEntity open = existing.get(0);
        throw new BusinessRuleException(
                "You have already asked for a "
                        + type.label()
                        + " on that account. The reference is "
                        + open.reference()
                        + " and it is still with us.");
    }

    private void refuseIfTooMany(UUID customerId) {
        if (requests.countByCustomerIdAndStatusIn(customerId, OPEN) >= MAX_OPEN_PER_CUSTOMER) {
            throw new BusinessRuleException(
                    "You have "
                            + MAX_OPEN_PER_CUSTOMER
                            + " requests with us already. Once some of those are done you can"
                            + " ask for more.");
        }
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

        return markReady(requestId, collectionPoint, null, staff);
    }

    /**
     * The same, and records the card that was produced.
     *
     * <p>THIS IS THE ONLY WAY A CARD ROW IS EVER CREATED. The member of staff is holding
     * the plastic and types what is on it; nothing in this service generates a card number,
     * a brand or an expiry date, because nothing in this service knows them. See V23.
     *
     * <p>{@code card} MAY BE NULL, and that is deliberate rather than lax. A cheque book has
     * no card details at all, and a card whose number nobody has typed yet is still ready to
     * collect -- the customer should be told where to fetch it either way. What must not
     * happen is a card row invented to stop the cards page looking empty.
     */
    @Transactional
    public ServiceRequestEntity markReady(
            UUID requestId,
            String collectionPoint,
            CardService.IssuedCard card,
            StaffEntity staff) {

        ServiceRequestEntity request = find(requestId);
        CustomerEntity customer = requireCustomer(request.customerId());

        /*
         * REFUSED BEFORE ANYTHING IS SAVED. Card details against a cheque book is a member
         * of staff looking at the wrong row, and the useful moment to say so is before the
         * customer has been emailed that their cheque book is ready.
         */
        if (card != null && request.requestType() != ServiceRequestType.CARD) {
            throw new BusinessRuleException(
                    "That request is for a "
                            + request.requestType().label()
                            + ", so it cannot carry card details.");
        }

        request.markedReady(staff.fullName(), collectionPoint);
        requests.save(request);

        if (card != null) {
            cards.issue(
                    request.customerId(),
                    request.accountId(),
                    request.id(),
                    card,
                    /*
                     * THE NAME AS IT IS TODAY, copied onto the card row rather than read
                     * from the customer at display time. People change their names; the
                     * plastic in their wallet does not.
                     */
                    customer.fullName(),
                    staff.fullName());
        }

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
     * Approves a loan application: creates the loan, pays the money in, closes the request.
     *
     * <p>WHY THIS IS NOT markReady AND THEN markCollected. That pair describes an object made
     * and then handed over a counter, which is what a cheque book is and what a loan is not.
     * Running a loan through it asked staff where the customer should collect it -- a question
     * with no answer -- and left the application recorded as something somebody came and
     * fetched. A loan is approved and the money appears in the account; there is no middle
     * state and nothing to hand over.
     *
     * <p>THE FIGURES ARE THE STAFF MEMBER'S, not the customer's. The application carries what
     * was asked for, which is where the screen's defaults come from, but a bank lends what it
     * decides to lend. What is granted is what arrives here.
     *
     * <p>ONE TRANSACTION FOR ALL OF IT. The loan, the disbursement, the closed application and
     * the notification commit together. A partial success here is a customer who owes money
     * that never arrived, or money that arrived against no loan.
     */
    @Transactional
    public ServiceRequestEntity grantLoan(
            UUID requestId,
            String productCode,
            Money principal,
            int months,
            BigDecimal ratePercent,
            StaffEntity staff) {

        ServiceRequestEntity request = find(requestId);
        CustomerEntity customer = requireCustomer(request.customerId());

        if (request.requestType() != ServiceRequestType.LOAN_APPLICATION) {
            throw new BusinessRuleException(
                    "That request is for a "
                            + request.requestType().label()
                            + ", so it cannot be granted as a loan.");
        }

        LoanEntity loan =
                loans.grant(
                        request.customerId(),
                        request.accountId(),
                        request.id(),
                        productCode,
                        principal,
                        months,
                        ratePercent,
                        staff.fullName());

        /*
         * CLOSED AFTER THE MONEY MOVED, not before. If granting fails the application is
         * still open and can be tried again; closing first would leave a customer with no
         * application and no loan.
         */
        request.granted(staff.fullName());
        requests.save(request);

        String mask =
                accounts.findById(request.accountId())
                        .map(CustomerAccountEntity::maskedNumber)
                        .orElse("your account");

        notifications.loanGranted(customer.id(), principal, mask, loan.installment());

        log.info(
                "Loan application {} granted as loan {} for customer {} by {}",
                request.reference(),
                loan.reference(),
                customer.customerNumber(),
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

        /*
         * AND THE CARD BECOMES USABLE, if one was recorded. PENDING_COLLECTION until
         * somebody hands it over: a card shown as ACTIVE while it is still in a drawer at
         * the branch is this portal telling the customer they hold something they do not.
         *
         * Silent when there is no card -- a cheque book reaches here too.
         */
        cards.activateForRequest(request.id());

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
