package rw.bank.ibanking.onboarding.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.onboarding.domain.AccountType;
import rw.bank.ibanking.onboarding.domain.ApplicationEntity;
import rw.bank.ibanking.onboarding.domain.ApplicationKind;
import rw.bank.ibanking.onboarding.domain.BusinessApplicationEntity;
import rw.bank.ibanking.onboarding.domain.CompanyEntity;
import rw.bank.ibanking.onboarding.domain.CorporateMembershipEntity;
import rw.bank.ibanking.onboarding.domain.CorporateRole;
import rw.bank.ibanking.onboarding.domain.BusinessDocumentEntity;
import rw.bank.ibanking.onboarding.domain.BusinessSignatoryEntity;
import rw.bank.ibanking.onboarding.domain.ApplicationStatus;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.CustomerStatus;
import rw.bank.ibanking.onboarding.domain.OutboxEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.domain.BeneficiaryStatus;
import rw.bank.ibanking.onboarding.domain.PasswordResetStatus;
import rw.bank.ibanking.onboarding.domain.ServiceRequestStatus;
import rw.bank.ibanking.onboarding.domain.TransferStatus;
import rw.bank.ibanking.onboarding.repo.ApplicationRepository;
import rw.bank.ibanking.onboarding.repo.BeneficiaryRepository;
import rw.bank.ibanking.onboarding.repo.PasswordResetRequestRepository;
import rw.bank.ibanking.onboarding.repo.ServiceRequestRepository;
import rw.bank.ibanking.onboarding.repo.TransferRepository;
import rw.bank.ibanking.onboarding.repo.BusinessApplicationRepository;
import rw.bank.ibanking.onboarding.repo.CompanyRepository;
import rw.bank.ibanking.onboarding.repo.CorporateMembershipRepository;
import rw.bank.ibanking.onboarding.repo.BusinessDocumentRepository;
import rw.bank.ibanking.onboarding.repo.BusinessSignatoryRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.OutboxRepository;

/**
 * The bank side of onboarding: an administrator creates the account, a manager releases it.
 *
 * <p>Two people, always. Issuing working credentials for a customer who does not exist is
 * the most valuable thing an insider can do, so no single member of staff can complete the
 * chain. That rule is enforced here, again in {@link CustomerEntity#approve(String)}, and
 * again as a CHECK constraint in the migration — three layers, because one forgotten
 * condition in a future service method should not be enough to break it.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    /*
     * THE TEMPORARY PASSWORD GENERATOR AND ITS LIFETIME MOVED to TemporaryPasswords, in
     * this package. They were private members here until a manager could also re-issue a
     * password for a customer who had forgotten theirs, at which point a second copy of a
     * credential generator was the alternative. See that class.
     */

    private final ApplicationRepository applications;
    private final CustomerRepository customers;
    private final PasswordEncoder passwords;
    private final Mailer mailer;
    private final Notifications notifications;
    private final OutboxRepository outbox;
    private final CustomerAccountRepository customerAccounts;
    private final AccountAccess access;
    private final AccountLedgerService ledger;
    private final BusinessApplicationRepository businesses;
    private final CompanyRepository companies;
    private final CorporateMembershipRepository memberships;
    private final BusinessSignatoryRepository signatories;
    private final BusinessDocumentRepository documents;

    /*
     * READ ONLY, AND ONLY FOR COUNTING. This service does not own transfers, password
     * requests, payees or service requests, and must not start acting on them from here —
     * each has its own service with its own rules. What it owns is the staff overview, and
     * an overview that cannot see four of the six queues is the thing being fixed.
     */
    private final TransferRepository transfers;
    private final PasswordResetRequestRepository passwordRequests;
    private final BeneficiaryRepository beneficiaries;
    private final ServiceRequestRepository serviceRequests;
    private final TrustedDevices trustedDevices;

    OnboardingService(
            ApplicationRepository applications,
            CustomerRepository customers,
            PasswordEncoder passwords,
            Mailer mailer,
            OutboxRepository outbox,
            CustomerAccountRepository customerAccounts,
            AccountAccess access,
            AccountLedgerService ledger,
            BusinessApplicationRepository businesses,
            CompanyRepository companies,
            CorporateMembershipRepository memberships,
            BusinessSignatoryRepository signatories,
            BusinessDocumentRepository documents,
            TrustedDevices trustedDevices,
            Notifications notifications,
            TransferRepository transfers,
            PasswordResetRequestRepository passwordRequests,
            BeneficiaryRepository beneficiaries,
            ServiceRequestRepository serviceRequests) {
        this.applications = applications;
        this.customers = customers;
        this.passwords = passwords;
        this.mailer = mailer;
        this.outbox = outbox;
        this.customerAccounts = customerAccounts;
        this.access = access;
        this.ledger = ledger;
        this.businesses = businesses;
        this.companies = companies;
        this.memberships = memberships;
        this.signatories = signatories;
        this.documents = documents;
        this.trustedDevices = trustedDevices;
        this.notifications = notifications;
        this.transfers = transfers;
        this.passwordRequests = passwordRequests;
        this.beneficiaries = beneficiaries;
        this.serviceRequests = serviceRequests;
    }

    /**
     * One account the administrator is entering for this customer.
     *
     * <p>A number, a type and a currency. A foreign-currency account is one of these with
     * a currency that is not RWF, not a type of its own; a customer holding both a current
     * and a savings account gives two of these, not a combined value — which is what lets
     * either one be removed, counted or reported on by itself later.
     */
    public record NewAccount(
            String accountNumber,
            AccountType accountType,
            String currency,
            /*
             * What is in the account today, as the administrator read it off the bank's
             * records. A decimal string rather than a number: it is parsed against the
             * account's own currency, and RWF has no decimal places while USD has two.
             */
            String openingBalance) {}

    /** The one and only time a temporary password exists outside a hash. */
    /**
     * No password here any more.
     *
     * <p>It is issued at approval and delivered by email, so there is nothing for this
     * response to carry and nothing for the admin screen to display.
     */
    public record CreatedAccount(
            CustomerEntity customer, List<CustomerAccountEntity> accounts) {}

    /* ------------------------------------------------------------ reading */

    @Transactional(readOnly = true)
    public List<ApplicationEntity> applications(ApplicationStatus status) {
        return status == null
                ? applications.findAllByOrderBySubmittedAtDesc()
                : applications.findByStatusOrderBySubmittedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public ApplicationEntity application(UUID id) {
        return applications
                .findById(id)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find that registration. It may already have"
                                                + " been dealt with."));
    }

    /** Everything a business application holds beyond the common fields. */
    public record BusinessDetail(
            BusinessApplicationEntity company,
            List<BusinessSignatoryEntity> signatories,
            List<BusinessDocumentEntity> documents) {}

    /**
     * The business detail for several applications, keyed by application id.
     *
     * <p>THREE QUERIES, whatever the number of rows. The registrations screen renders
     * every application it is given, so a per-row lookup would be a query count that
     * grows with the bank's intake — fast on a developer's three applications and slow on
     * a branch's three hundred. Personal applications simply have no entry.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BusinessDetail> businessDetails(List<UUID> applicationIds) {
        if (applicationIds.isEmpty()) return Map.of();

        Map<UUID, List<BusinessSignatoryEntity>> byApplication =
                signatories.findByApplicationIdInOrderByOrdinalAsc(applicationIds).stream()
                        .collect(Collectors.groupingBy(BusinessSignatoryEntity::applicationId));

        Map<UUID, List<BusinessDocumentEntity>> docsByApplication =
                documents.findByApplicationIdIn(applicationIds).stream()
                        .collect(Collectors.groupingBy(BusinessDocumentEntity::applicationId));

        return businesses.findByApplicationIdIn(applicationIds).stream()
                .collect(
                        Collectors.toMap(
                                BusinessApplicationEntity::applicationId,
                                company ->
                                        new BusinessDetail(
                                                company,
                                                byApplication.getOrDefault(
                                                        company.applicationId(), List.of()),
                                                docsByApplication.getOrDefault(
                                                        company.applicationId(), List.of()))));
    }

    @Transactional(readOnly = true)
    public List<CustomerEntity> customers(CustomerStatus status) {
        return status == null
                ? customers.findAllByOrderByCreatedAtDesc()
                : customers.findByStatusOrderByCreatedAtDesc(status);
    }

    /**
     * The most recent messages the bank sent.
     *
     * <p>Capped rather than unbounded: this is a diagnostic screen, and a query with no
     * limit is one that gets slower every day until somebody notices in production.
     */
    @Transactional(readOnly = true)
    public List<OutboxEntity> recentMessages() {
        return outbox.findAllByOrderBySentAtDesc(Limit.of(100));
    }

    /**
     * WHAT IS WAITING FOR SOMEBODY AT THE BANK, across every queue.
     *
     * <p>WHAT THIS REPLACED, and why it was the wrong shape. It used to be five onboarding
     * figures and nothing else, which made the staff overview answer one question — "who is
     * waiting for an account?" — and stay silent about the other five queues the service
     * has since grown. A manager opening it saw numbers that are mostly an administrator's
     * job and no sign of their own: money waiting to be released, passwords to re-issue,
     * payees to check, cards to make.
     *
     * <p>SO IT IS NAMED FOR THE WHOLE JOB NOW. `OnboardingSummary` had stopped being true,
     * and a type whose name is a lie is one nobody adds the right field to.
     *
     * <p>Declared here rather than on the controller because the service owns what the
     * numbers mean; the controller only decides they are reachable over HTTP. A service
     * importing a web class would point the dependency the wrong way round.
     *
     * <p>EVERY FIGURE IS COUNTED IN THE DATABASE, never by loading a queue and taking its
     * size. The queue screens ask for a bounded page, so their size is the page rather than
     * the backlog, and a manager told "12 waiting" when 200 are waiting has been handed a
     * worse number than none at all.
     */
    public record StaffSummary(
            /* --- onboarding: an administrator creates, a manager releases --- */
            long submitted,
            long awaitingApproval,
            long active,
            long rejected,
            long awaitingFirstSignIn,

            /* --- the queues, each one somebody's actual work --- */

            /**
             * Money that has left an account and is waiting for a manager.
             *
             * <p>FIRST AMONG THE QUEUES WHEREVER IT IS SHOWN. Every other figure here is
             * somebody waiting; this one is somebody's money in flight, debited and not yet
             * delivered. It is the only queue where a slow day costs the customer rather
             * than inconveniencing them.
             */
            long transfersToRelease,
            long passwordRequests,
            long payeesToCheck,
            long cardsAndChequeBooks) {}

    /** Counted in the database. */
    @Transactional(readOnly = true)
    public StaffSummary summary() {
        return new StaffSummary(
                applications.countByStatus(ApplicationStatus.SUBMITTED),
                customers.countByStatus(CustomerStatus.PENDING_APPROVAL),
                customers.countByStatus(CustomerStatus.ACTIVE),
                customers.countByStatus(CustomerStatus.REJECTED),
                /*
                 * Approved and never signed in. These are accounts whose temporary
                 * password a member of staff read off a screen and handed over, and which
                 * nobody has taken ownership of yet — so a figure that stops falling is a
                 * queue of live credentials sitting on desks, not a quiet week.
                 */
                customers.countByStatusAndMustChangePasswordTrue(CustomerStatus.ACTIVE),
                transfers.countByStatus(TransferStatus.PENDING_APPROVAL),
                passwordRequests.countByStatus(PasswordResetStatus.PENDING),
                beneficiaries.countByStatus(BeneficiaryStatus.PENDING_VERIFICATION),
                /*
                 * SUBMITTED and READY together, because both are still somebody's job: one
                 * has to be made, the other handed over. A count of SUBMITTED alone would
                 * show an empty queue while cards sat on a counter uncollected.
                 */
                serviceRequests.countByStatusIn(
                        List.of(ServiceRequestStatus.SUBMITTED, ServiceRequestStatus.READY)));
    }

    /**
     * Admits the company an approved business application describes.
     *
     * <p>Idempotent by constraint rather than by check: `companies.application_id` is
     * unique, so a double-submitted form cannot produce two companies for one
     * application. The status check in {@link #createAccount} catches it first; this is
     * what holds if that check is ever moved or a request is retried underneath it.
     */
    private CompanyEntity admitCompany(ApplicationEntity application, StaffEntity admin) {
        var existing = companies.findByApplicationId(application.id());
        if (existing.isPresent()) return existing.get();

        /*
         * The registration number and TIN come from the business application. If it is
         * missing, the application was created by a path that did not store one — which
         * is a fault in this service rather than something to paper over with blanks on a
         * company record the bank will rely on.
         */
        BusinessApplicationEntity detail =
                businesses
                        .findByApplicationId(application.id())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A business application reached account"
                                                    + " creation with no company details"
                                                    + " stored."));

        return companies.save(
                CompanyEntity.admitted(
                        application.id(),
                        detail.companyName(),
                        detail.registrationNumber(),
                        detail.tin(),
                        admin.fullName()));
    }

    /* ------------------------------------------------- admin: create the login */

    /**
     * Creates a customer login for an application, and issues a temporary password.
     *
     * <p>The password is returned to the caller exactly once, so the administrator can hand
     * it over in person. Only its hash is stored, so it cannot be read back — not by the
     * administrator who created it, not by support, not from a database dump.
     */
    @Transactional
    public CreatedAccount createAccount(
            UUID applicationId, List<NewAccount> newAccounts, StaffEntity admin) {
        ApplicationEntity application = application(applicationId);

        if (application.status() != ApplicationStatus.SUBMITTED) {
            throw new BusinessRuleException(
                    "An account has already been created for this registration.");
        }

        /*
         * JOINING an existing company is still refused, and only that.
         *
         * It is not an application for a new login at all — it asks a company's own
         * administrator for access to a company that already banks here, which is a
         * different decision made by a different person. Nothing in this method would
         * produce it, so it refuses rather than approximating.
         */
        if (application.kind() == ApplicationKind.JOIN_BUSINESS) {
            throw new BusinessRuleException(
                    "This is a request to join a company that already banks here. It is"
                            + " granted by that company's own administrator, not from this"
                            + " screen.");
        }

        if (customers.existsByEmailIgnoreCase(application.email())) {
            throw new BusinessRuleException(
                    "A customer already exists with that email address.");
        }

        /*
         * Created with an UNUSABLE credential, on purpose.
         *
         * The temporary password is generated when a manager approves, not here, because
         * that is when it is emailed — and only the hash is ever stored, so a password
         * made at this step would no longer exist in readable form by the time the
         * message is composed.
         *
         * The side effect is worth having: no member of staff, including the
         * administrator doing this, ever sees a working password for a customer's
         * account. Until approval there is simply no credential that opens it.
         */
        boolean isCompany = application.kind() == ApplicationKind.BUSINESS;

        /*
         * THE COMPANY, admitted before the login that will act for it.
         *
         * Created from the business application's own details rather than from the
         * parent row, because `displayName` there is the company's name but the
         * registration number and TIN are not on it. Copied rather than joined, so what
         * the bank admitted stays what the bank admitted even if the application is
         * later corrected.
         */
        CompanyEntity company = isCompany ? admitCompany(application, admin) : null;

        /*
         * WHOSE NAME IS ON THE LOGIN, and this is the part worth reading twice.
         *
         * For a personal application it is the applicant. For a company it is the NAMED
         * CONTACT, not the company — `application.displayName()` is the company's name,
         * and a login called "Inyange Industries Ltd" with a person's email address
         * behind it is a record of nobody. The contact's name comes off the business
         * application; the email and phone on the parent row are already theirs, because
         * theirs is the address the verification code went to.
         */
        var businessDetail =
                isCompany ? businesses.findByApplicationId(application.id()) : java.util.Optional.<BusinessApplicationEntity>empty();

        String loginHolderName =
                businessDetail.map(BusinessApplicationEntity::contactFullName)
                        .orElse(application.displayName());

        CustomerEntity customer =
                isCompany
                        ? CustomerEntity.corporatePendingApproval(
                                application.id(),
                                loginHolderName,
                                application.email(),
                                application.phone(),
                                nextCustomerNumber(),
                                passwords.encode(TemporaryPasswords.generate()),
                                admin.fullName())
                        : CustomerEntity.pendingApproval(
                                application.id(),
                                application.displayName(),
                                application.email(),
                                application.phone(),
                                nextCustomerNumber(),
                                passwords.encode(TemporaryPasswords.generate()),
                                admin.fullName());
        customers.save(customer);

        /*
         * The membership is what actually grants access to the company's accounts — not
         * the customer's type, and not the accounts' own columns. ADMIN because this is
         * the contact the company named: they are the person the bank deals with, and
         * the one who will need to give colleagues access once that flow exists.
         *
         * ONE MEMBER. Additional signatories at their own limits are a mandate decision
         * the bank owes (docs/OPEN-ITEMS.md), and inventing them here would mean this
         * service deciding who may move a company's money.
         */
        if (company != null) {
            memberships.save(
                    CorporateMembershipEntity.granted(
                            company.id(),
                            customer.id(),
                            CorporateRole.ADMIN,
                            admin.fullName()));
        }

        application.accountCreated(customer.id());
        applications.save(application);

        /*
         * Recorded, but NOT emailed to the customer.
         *
         * The temporary password travels by hand. This message is the internal record that
         * an account was created, and it deliberately carries no credential — see
         * EmailTemplates.
         */
        mailer.send(
                OutboxKind.ACCOUNT_CREATED,
                application.email(),
                "Your Zigama CSS application is being reviewed",
                String.join(
                        "\n",
                        "Dear " + application.displayName() + ",",
                        "",
                        "We have created your internet banking account and it is now with a",
                        "manager for approval. We will email you again as soon as it is ready.",
                        "",
                        "Zigama CSS"));

        /*
         * The customer's accounts, as the administrator read them off the bank's records.
         *
         * THESE ARE THE ACCOUNTS. Not a request, not a queue, not something waiting on
         * another system — the accounts already exist at Zigama, and the administrator is
         * telling this service which ones belong to this person. The customer sees exactly
         * these when they sign in.
         *
         * Nothing here verifies them, and that is the design: checking the bank's records
         * is the administrator's job, done before they got to this screen. `assigned_by`
         * is the record that it was done.
         */
        List<CustomerAccountEntity> assigned =
                newAccounts.stream()
                        .map(
                                entry ->
                                        company == null
                                                ? CustomerAccountEntity.assigned(
                                                        customer.id(),
                                                        entry.accountNumber(),
                                                        entry.accountType(),
                                                        entry.currency(),
                                                        admin.fullName())
                                                /*
                                                 * TO THE COMPANY, not to the contact. A
                                                 * company's money is not the finance
                                                 * director's money: held against the
                                                 * person, removing their access would
                                                 * orphan the accounts and a second
                                                 * signatory would need a duplicate row
                                                 * for each — two records of one balance.
                                                 */
                                                : CustomerAccountEntity.assignedToCompany(
                                                        customer.id(),
                                                        company.id(),
                                                        entry.accountNumber(),
                                                        entry.accountType(),
                                                        entry.currency(),
                                                        admin.fullName()))
                        .toList();

        /*
         * Refused if any of them is already on someone else's profile, BEFORE any is
         * saved. Two logins reaching one account is either a joint account — which the
         * bank has given no rule for — or somebody being handed a stranger's money, and
         * only one of those is safe to assume.
         */
        for (NewAccount entry : newAccounts) {
            var held = customerAccounts.findByAccountNumberAndRemovedAtIsNull(
                    entry.accountNumber().trim());
            if (!held.isEmpty()) {
                throw new BusinessRuleException(
                        "One of those accounts is already on another customer's profile. "
                                + "If it is a joint account, refer it rather than entering it here.");
            }
        }

        customerAccounts.saveAll(assigned);

        /*
         * The opening balances, each as a CREDIT rather than a number set directly.
         *
         * It would be simpler to write the figure into the balance column, and that is
         * exactly what makes it wrong: the balance is the sum of the ledger, and a
         * starting figure with no entry behind it breaks that on day one. A statement
         * would begin mid-story, with money that appeared from nowhere.
         */
        for (int i = 0; i < assigned.size(); i++) {
            CustomerAccountEntity account = assigned.get(i);
            String opening = newAccounts.get(i).openingBalance();
            ledger.openWith(
                    account,
                    Money.parse(opening == null || opening.isBlank() ? "0" : opening,
                            account.currency()),
                    admin.fullName(),
                    /*
                     * Derived from the account id, so re-running this for the same account
                     * can never post a second opening balance.
                     */
                    "opening-" + account.id());
        }

        /*
         * Masks in the log, never the numbers. A full account number on a log line is a
         * full account number in every aggregator, backup and screenshot thereafter.
         */
        log.info(
                "Account created for application {} by {}, with accounts {}",
                application.reference(),
                admin.email(),
                assigned.stream().map(CustomerAccountEntity::maskedNumber).toList());

        return new CreatedAccount(customer, assigned);
    }

    /* ------------------------------------------- the customer's accounts */

    /**
     * What this customer can see when they sign in.
     *
     * <p>Delegated to {@link AccountAccess} rather than queried here, because the ledger
     * has to answer the same question before it moves money and the two answers must not
     * be able to differ. This used to be a single query on {@code customer_id}, which was
     * the whole rule when a personal account was the only kind; a company's accounts are
     * reached through membership instead, and a second copy of that rule here is how the
     * screen and the payment end up disagreeing about whose money it is.
     */
    @Transactional(readOnly = true)
    public List<CustomerAccountEntity> accountsFor(UUID customerId) {
        return access.accountsFor(customerId);
    }

    /**
     * The same for many customers, in one query — the staff list renders every row.
     *
     * <p>STAFF-SIDE, AND DELIBERATELY STILL KEYED ON {@code customer_id}. This answers
     * "what did an administrator enter against this login", which is what the staff list
     * shows and is a different question from what the customer may reach. A company
     * account appears under the contact it was entered against, which is the row a member
     * of staff would go to in order to correct it.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<CustomerAccountEntity>> accountsFor(List<CustomerEntity> forCustomers) {
        if (forCustomers.isEmpty()) return Map.of();

        return customerAccounts
                .findByCustomerIdInAndRemovedAtIsNullOrderByAssignedAtAsc(
                        forCustomers.stream().map(CustomerEntity::id).toList())
                .stream()
                .collect(Collectors.groupingBy(CustomerAccountEntity::customerId));
    }

    /**
     * Takes an account off a customer's profile.
     *
     * <p>For the case that matters: a wrong number was typed, and somebody is seeing an
     * account that is not theirs. A reason is required, and the row survives — if a
     * mistake like that has been made, who made it and who caught it is the first thing
     * anyone will ask.
     */
    @Transactional
    public CustomerAccountEntity removeAccount(
            UUID customerId, UUID accountId, String reason, StaffEntity manager) {

        CustomerEntity customer = findCustomer(customerId);

        CustomerAccountEntity account =
                customerAccounts
                        .findById(accountId)
                        .filter(found -> found.customerId().equals(customerId))
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "We could not find that account."));

        if (!account.isActive()) {
            throw new BusinessRuleException("That account has already been removed.");
        }

        account.remove(manager.fullName(), reason);
        customerAccounts.save(account);

        log.warn(
                "Account {} removed from customer {} by {}: {}",
                account.maskedNumber(),
                customer.customerNumber(),
                manager.email(),
                reason);

        return account;
    }

    /** One customer, or 404. */
    @Transactional(readOnly = true)
    public CustomerEntity customer(UUID customerId) {
        return findCustomer(customerId);
    }

    /* --------------------------------------------- manager: approve or reject */

    /**
     * Releases the account and emails the customer.
     *
     * <p>This is the manager's step, and the email is the point of it: until now the
     * customer has been told nothing since they registered.
     */
    @Transactional
    public CustomerEntity approve(UUID customerId, StaffEntity manager) {
        CustomerEntity customer = findCustomer(customerId);

        // Throws if this manager created it, or if it has already been decided.
        customer.approve(manager.fullName());

        /*
         * The credential is made HERE, and this is the only place it exists in readable
         * form — in this local variable and in the message composed from it below.
         */
        String temporaryPassword = TemporaryPasswords.generate();
        customer.issueTemporaryPassword(
                passwords.encode(temporaryPassword),
                Instant.now().plus(TemporaryPasswords.VALIDITY));
        customers.save(customer);

        applications
                .findById(customer.applicationId())
                .ifPresent(
                        application -> {
                            application.approved();
                            applications.save(application);
                        });

        mailer.send(
                OutboxKind.ACCOUNT_APPROVED,
                customer.email(),
                EmailTemplates.APPROVED_SUBJECT,
                EmailTemplates.approved(
                        customer.fullName(),
                        customer.customerNumber(),
                        /*
                         * The address sign-in actually accepts. Passed explicitly rather
                         * than left for the template to imply: CustomerAuthService looks a
                         * customer up by email and by nothing else.
                         */
                        customer.email(),
                        temporaryPassword,
                        TemporaryPasswords.VALIDITY));

        log.info("Customer {} approved by {}", customer.customerNumber(), manager.email());
        return customer;
    }

    /* --------------------------------------------------- manager: freezing */

    /**
     * Stops a customer using the service.
     *
     * <p>NOT subject to the four-eyes rule, unlike creating an account, and that is a
     * considered difference rather than an oversight. Four eyes exists to stop one person
     * granting access alone. Freezing REMOVES access — it is the protective direction —
     * and making a manager find a colleague before they can stop a login they believe is
     * compromised would cost exactly the minutes that matter.
     *
     * <p>Unfreezing restores access and so is the direction that carries risk. It is
     * manager-only and reasoned, but any manager may do it; whether it should require a
     * different manager from the one who froze it is a policy question for the bank, and
     * is flagged in frontend/docs/OPEN-ITEMS.md rather than invented here.
     */
    @Transactional
    public CustomerEntity freeze(UUID customerId, String reason, StaffEntity manager) {
        if (reason == null || reason.isBlank()) {
            /*
             * Mandatory, and this is the only record of it. A frozen account with no
             * stated reason is one nobody can safely unfreeze later, because nobody knows
             * what they would be undoing.
             */
            throw new BusinessRuleException(
                    "Give a reason for freezing this account, so whoever reviews it later knows"
                            + " what happened.");
        }

        CustomerEntity customer = findCustomer(customerId);
        customer.freeze(manager.fullName(), reason);
        customers.save(customer);

        /*
         * AND EVERY BROWSER THEY HAD ALREADY PROVED.
         *
         * Signing in is refused for a frozen customer anyway, so this changes nothing
         * while the freeze is on. It changes what the freeze MEANT once the account is
         * restored: without it, a browser trusted before the freeze would still sign in
         * on the password alone afterwards — and if the freeze happened because somebody
         * else had that password and that machine, restoring the account would hand it
         * straight back to them.
         */
        trustedDevices.revokeAll(customerId, "Account frozen: " + reason);

        /*
         * The customer is told, but not why. See EmailTemplates.frozen — the reason may
         * concern an investigation, and the bank does not know from here which kind of
         * freeze this is.
         */
        mailer.send(
                OutboxKind.ACCOUNT_FROZEN,
                customer.email(),
                EmailTemplates.FROZEN_SUBJECT,
                EmailTemplates.frozen(customer.fullName()));

        /*
         * WARN, not INFO. Somebody losing access to their bank is worth finding in a log
         * without knowing to look for it.
         */
        log.warn(
                "Customer {} FROZEN by {}: {}",
                customer.customerNumber(),
                manager.email(),
                reason);
        return customer;
    }

    /** Gives the account back. */
    @Transactional
    public CustomerEntity unfreeze(UUID customerId, String reason, StaffEntity manager) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException(
                    "Give a reason for restoring this account, so the history makes sense.");
        }

        CustomerEntity customer = findCustomer(customerId);
        customer.unfreeze(manager.fullName(), reason);
        customers.save(customer);

        mailer.send(
                OutboxKind.ACCOUNT_UNFROZEN,
                customer.email(),
                EmailTemplates.UNFROZEN_SUBJECT,
                EmailTemplates.unfrozen(customer.fullName()));

        /*
         * THE ONLY ACCOUNT-STATUS EVENT THAT GETS AN IN-APP NOTIFICATION, and the reason is
         * readability rather than importance. A customer can sign in again after a freeze is
         * lifted, so they will see this. They cannot sign in while frozen, nor before
         * approval, nor after rejection — so a notification for any of those would be a row
         * nobody could ever read, which would make the table look more complete than the
         * product is. See NotificationKind.
         */
        notifications.accountUnfrozen(customer.id());

        log.warn(
                "Customer {} unfrozen by {}: {}",
                customer.customerNumber(),
                manager.email(),
                reason);
        return customer;
    }

    @Transactional
    public CustomerEntity reject(UUID customerId, String reason, StaffEntity manager) {
        if (reason == null || reason.isBlank()) {
            /*
             * A reason is mandatory. A rejection nobody can explain is one the customer
             * cannot act on and the bank cannot defend, and this is the only record of
             * why it happened.
             */
            throw new BusinessRuleException("Give a reason so the applicant can be told why.");
        }

        CustomerEntity customer = findCustomer(customerId);

        /*
         * The temporary password is replaced with the hash of a fresh random value nobody
         * holds, rather than left in place. It had been seen by at least one member of
         * staff, and an account that could later be un-rejected must not come back with a
         * credential a stranger still knows.
         */
        customer.reject(manager.fullName(), passwords.encode(TemporaryPasswords.generate()));
        customers.save(customer);

        applications
                .findById(customer.applicationId())
                .ifPresent(
                        application -> {
                            application.rejected(reason);
                            applications.save(application);
                        });

        mailer.send(
                OutboxKind.ACCOUNT_REJECTED,
                customer.email(),
                EmailTemplates.REJECTED_SUBJECT,
                EmailTemplates.rejected(customer.fullName(), reason));

        log.info("Customer {} rejected by {}", customer.customerNumber(), manager.email());
        return customer;
    }

    /* ------------------------------------------------------------ plumbing */

    private CustomerEntity findCustomer(UUID id) {
        return customers
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("We could not find that customer."));
    }

    /**
     * The next customer number.
     *
     * <p>Derived from a count, which is adequate while one instance creates accounts one at
     * a time behind a transaction, and is NOT adequate for two instances: both would read
     * the same count. A database sequence is the right answer before this runs anywhere
     * with more than one replica. Flagged rather than pretended.
     */
    private String nextCustomerNumber() {
        return String.format(Locale.ROOT, "ZG%07d", 3_100_000 + customers.count() + 1);
    }
}
