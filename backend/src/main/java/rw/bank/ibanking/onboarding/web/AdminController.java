package rw.bank.ibanking.onboarding.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.AccountType;
import rw.bank.ibanking.onboarding.domain.ApplicationEntity;
import rw.bank.ibanking.onboarding.domain.ApplicationStatus;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.CustomerStatus;
import rw.bank.ibanking.onboarding.domain.OutboxEntity;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.service.BeneficiaryService;
import rw.bank.ibanking.onboarding.service.OnboardingService;
import rw.bank.ibanking.onboarding.service.PasswordResetService;
import rw.bank.ibanking.onboarding.service.ServiceRequestService;
import rw.bank.ibanking.onboarding.service.TransferService;
import rw.bank.ibanking.onboarding.service.StaffUserDetailsService;

/**
 * The bank's own endpoints.
 *
 * <p>Roles are enforced with {@code @PreAuthorize} on each method, not by which screen the
 * caller came from. Hiding a button is a courtesy; this is the control. An administrator
 * calling the approve endpoint directly gets 403, and so does a manager calling
 * create-account — both are covered by tests, because "the UI does not offer it" is not a
 * security property.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController {

    private final OnboardingService onboarding;
    private final StaffUserDetailsService staff;
    private final TransferService transfers;
    private final CustomerAccountRepository customerAccounts;
    private final PasswordResetService passwordResets;
    private final BeneficiaryService beneficiaries;
    private final ServiceRequestService serviceRequests;

    AdminController(
            OnboardingService onboarding,
            StaffUserDetailsService staff,
            TransferService transfers,
            CustomerAccountRepository customerAccounts,
            PasswordResetService passwordResets,
            BeneficiaryService beneficiaries,
            ServiceRequestService serviceRequests) {
        this.onboarding = onboarding;
        this.staff = staff;
        this.transfers = transfers;
        this.customerAccounts = customerAccounts;
        this.passwordResets = passwordResets;
        this.beneficiaries = beneficiaries;
        this.serviceRequests = serviceRequests;
    }

    /* ------------------------------------------------------------- shapes */

    record ApplicationSummary(
            String id,
            String reference,
            String kind,
            String status,
            String displayName,
            String email,
            String phone,
            boolean emailVerified,
            String submittedAt,
            String accountNumber,
            String nationalId,
            String dateOfBirth,
            String rejectionReason,
            /**
             * Anything this kind of application holds beyond the fields above.
             *
             * <p>A LABEL/VALUE LIST, because the staff screen already renders one and
             * because the alternative is a DTO that grows a nullable block per
             * registration kind — a company's dozen columns sitting unused on every
             * personal application, and the next kind adding another dozen.
             *
             * <p>Presentation only. Nothing reads these back, nothing keys off the
             * labels, and the structured rows they are built from stay in the database
             * for the queries that need them. Empty for a personal application, whose
             * identity fields are the named ones above.
             */
            List<DetailField> details) {

        static ApplicationSummary of(ApplicationEntity a, List<DetailField> details) {
            return new ApplicationSummary(
                    a.id().toString(),
                    a.reference(),
                    a.kind().name(),
                    a.status().name(),
                    a.displayName(),
                    a.email(),
                    a.phone(),
                    a.emailVerified(),
                    a.submittedAt().toString(),
                    a.accountNumber(),
                    a.nationalId(),
                    a.dateOfBirth() == null ? null : a.dateOfBirth().toString(),
                    a.rejectionReason(),
                    details);
        }
    }

    /** One extra field on an application, for display beside the common ones. */
    record DetailField(String label, String value) {}

    /**
     * The company detail an administrator reads before deciding.
     *
     * <p>NOTHING HERE IS VERIFIED, and the labels say so where it matters. This service
     * has no company register and no tax authority to ask; the administrator is comparing
     * these against the bank's own records, which is the only check there is.
     */
    private static List<DetailField> businessFields(OnboardingService.BusinessDetail detail) {
        var company = detail.company();
        List<DetailField> fields = new ArrayList<>();

        fields.add(new DetailField("Company registration number", company.registrationNumber()));
        fields.add(new DetailField("TIN", company.tin()));
        fields.add(new DetailField("Type of business", company.businessType()));
        fields.add(new DetailField("Sector", company.sector()));
        fields.add(new DetailField("Registered address", company.address()));
        fields.add(new DetailField("Company email", company.companyEmail()));
        fields.add(new DetailField("Company phone", company.companyPhone()));

        if (company.existingAccountNumber() != null) {
            fields.add(
                    new DetailField(
                            "Existing account number (claimed)", company.existingAccountNumber()));
        }

        fields.add(
                new DetailField(
                        "Our contact",
                        company.contactFullName() + " · " + company.contactRole()));

        /*
         * Signatories in the order the applicant listed them. The first is conventionally
         * the primary, and re-sorting would throw away information only the applicant
         * had.
         */
        for (var person : detail.signatories()) {
            String value =
                    person.fullName()
                            + " · "
                            + person.role()
                            + " · ID "
                            + person.nationalId()
                            + (person.email() == null ? "" : " · " + person.email());
            fields.add(new DetailField("Signatory " + person.ordinal(), value));
        }

        /*
         * THE DOCUMENTS SAY THEY ARE NOT HELD, on every row.
         *
         * The wizard collects file names and there is no upload endpoint behind it. An
         * administrator reading "Certificate.pdf" against a document label would
         * reasonably assume the bank has the file; saying "not received" on the line
         * itself is the only place that assumption can be corrected at the moment it
         * would be made.
         */
        for (var document : detail.documents()) {
            fields.add(
                    new DetailField(
                            "Document listed",
                            document.fileName()
                                    + (document.received()
                                            ? ""
                                            : " — not received, ask the applicant for it")));
        }

        return fields;
    }

    /**
     * What the staff screens read about a customer.
     *
     * <p>Every field the portal's {@code Customer} type declares appears here, with the
     * same name. That is not tidiness — the two drifted apart and the portal crashed
     * twice on it: once reading {@code details} that did not exist, then reading
     * {@code accountMasks.join(...)} on undefined, straight after an administrator had
     * pressed "create account". TypeScript cannot catch this, because the type describes
     * what the API is *believed* to return.
     *
     * <p>{@code createdByName} and {@code approvedByName}, not {@code createdBy} — the
     * values are people's names, and the portal already called them that.
     */
    record CustomerSummary(
            String id,
            String applicationId,
            String fullName,
            String email,
            String phone,
            String customerNumber,
            String userType,
            String status,
            boolean mustChangePassword,
            String createdAt,
            String createdByName,
            String approvedAt,
            String approvedByName,
            String rejectionReason,
            List<String> accountMasks,
            /*
             * Shown to STAFF only. The reason may concern an investigation the customer
             * must not be tipped off about, which is why the frozen email carries none of
             * this and no customer-facing endpoint returns it.
             */
            String frozenBy,
            String frozenAt,
            String freezeReason,
            /*
             * What the branch asked to have opened, shown to STAFF.
             *
             * Always populated, never omitted. If a response could leave it out, an empty
             * list would mean both "nothing was requested" and "nobody looked", and a
             * manager about to approve an account could not tell those apart.
             *
             * These are REQUESTS, not accounts. accountMasks stays empty until core
             * banking opens something.
             */
            List<CustomerAccountView> accounts) {

        static CustomerSummary of(CustomerEntity c, List<CustomerAccountEntity> accounts) {
            return new CustomerSummary(
                    c.id().toString(),
                    c.applicationId().toString(),
                    c.fullName(),
                    c.email(),
                    c.phone(),
                    c.customerNumber(),
                    /*
                     * From the customer. This was the literal "RETAIL", which was true
                     * while a personal login was the only kind this service created and
                     * would now be a lie on a company's contact — on the staff screen,
                     * which is where somebody checks what they just created.
                     */
                    c.userType().name(),
                    c.status().name(),
                    c.mustChangePassword(),
                    c.createdAt().toString(),
                    c.createdBy(),
                    c.approvedAt() == null ? null : c.approvedAt().toString(),
                    c.approvedBy(),
                    /*
                     * Null, not a message. The rejection reason lives on the application,
                     * and duplicating it here would be a second copy to keep in step.
                     */
                    null,
                    /*
                     * REAL, at last, and only ever masked.
                     *
                     * This was a hardcoded empty list, which was honest while nothing
                     * could produce an account number and permanent because of it. It now
                     * comes from the accounts the administrator entered — and it is the
                     * MASK that travels. CustomerAccountEntity keeps the full number
                     * package-private precisely so this line cannot be the one that leaks
                     * it.
                     */
                    accounts.stream().map(CustomerAccountEntity::maskedNumber).toList(),
                    c.frozenBy(),
                    c.frozenAt() == null ? null : c.frozenAt().toString(),
                    c.freezeReason(),
                    accounts.stream().map(CustomerAccountView::of).toList());
        }
    }

    /**
     * No password in this response, and no endpoint anywhere returns one.
     *
     * <p>This used to be "the only response in the service that carries a password",
     * shown once to the administrator to hand over in person. The bank decided the
     * temporary password should be emailed instead, so it is now generated at approval
     * and exists only inside that call and the message it composes.
     *
     * <p>The consequence is worth stating plainly: no member of staff ever sees a working
     * password for a customer's account. "Let me look up the customer's password" was
     * already a sentence support staff could not complete; now neither can the
     * administrator who created the account.
     */
    /*
     * The customer carries its own `accounts`, echoed back from what was stored rather
     * than left for the screen to assume from what was typed. That gap is exactly what let
     * a discarded request body go unnoticed for as long as it did.
     */
    record CreatedAccountResponse(CustomerSummary customer) {}

    record RejectRequest(
            @NotBlank(message = "Give a reason so the applicant can be told why.")
                    @Size(max = 500)
                    String reason) {}

    /* ---------------------------------------------------------- reading */

    /*
     * ONE BATCH LOOKUP for the whole list, not one per row. See
     * OnboardingService.businessDetails.
     */
    @GetMapping("/applications")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    List<ApplicationSummary> applications(@RequestParam(required = false) ApplicationStatus status) {
        List<ApplicationEntity> found = onboarding.applications(status);

        var business =
                onboarding.businessDetails(
                        found.stream().map(ApplicationEntity::id).toList());

        return found.stream()
                .map(
                        a -> {
                            var detail = business.get(a.id());
                            return ApplicationSummary.of(
                                    a, detail == null ? List.of() : businessFields(detail));
                        })
                .toList();
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    ApplicationSummary application(@PathVariable UUID id) {
        ApplicationEntity found = onboarding.application(id);
        var detail = onboarding.businessDetails(List.of(found.id())).get(found.id());
        return ApplicationSummary.of(
                found, detail == null ? List.of() : businessFields(detail));
    }

    /** A summary with its account requests loaded. See the field's comment for why always. */
    private CustomerSummary summaryOf(CustomerEntity customer) {
        return CustomerSummary.of(customer, onboarding.accountsFor(customer.id()));
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    List<CustomerSummary> customers(@RequestParam(required = false) CustomerStatus status) {
        var found = onboarding.customers(status);
        /*
         * One query for every row's account requests, not one per row. See
         * OnboardingService.accountRequestsFor(List).
         */
        var accounts = onboarding.accountsFor(found);

        return found.stream()
                .map(c -> CustomerSummary.of(c, accounts.getOrDefault(c.id(), List.of())))
                .toList();
    }

    /* ------------------------------------------------- admin only: create */

    /**
     * Note the parameter: {@link Authentication}, not
     * {@code @AuthenticationPrincipal UserDetails}.
     *
     * <p>It used to be the latter, and it was null in production use while every test
     * passed. The tests authenticate with HTTP Basic, which puts a {@code UserDetails} in
     * the principal; the staff portal signs in at {@code /auth/staff/login} and gets a
     * cookie session whose principal is the member's email as a plain String. So the
     * parameter bound to null, and the first thing this method did was call a method on
     * it — a NullPointerException, surfaced to the administrator as "something went wrong
     * on our side", at the exact moment they were trying to create a customer's login.
     *
     * <p>{@code Authentication.getName()} is what both mechanisms agree on.
     */
    @PostMapping("/applications/{id}/create-account")
    @PreAuthorize("hasRole('ADMIN')")
    CreatedAccountResponse createAccount(
            @PathVariable UUID id,
            @Valid @RequestBody CreateAccountRequest request,
            Authentication caller) {

        var created =
                onboarding.createAccount(
                        id, request.toNewAccounts(), staff.require(caller.getName()));

        return new CreatedAccountResponse(
                CustomerSummary.of(created.customer(), created.accounts()));
    }

    /**
     * The accounts the branch is asking to have opened.
     *
     * <p>THIS PARAMETER IS THE FIX. The method had no {@code @RequestBody} at all, so the
     * account type, currency and opening balance the screen had been posting since the
     * form was built were read by nothing — Spring bound the path variable and discarded
     * the rest, and the administrator was shown a success message. A body that is not
     * declared is not ignored loudly; it is ignored silently, which is why this went
     * unnoticed.
     *
     * <p>A LIST, not one account. "A current account and a savings account" is two
     * accounts; a foreign-currency account is one of these with a currency that is not
     * RWF. Neither is a compound type, and encoding them as one would produce values
     * nothing can later close, count or report on individually.
     */
    record CreateAccountRequest(
            @Valid
                    @NotEmpty(message = "Enter at least one account for this customer.")
                    @Size(max = 10, message = "That is more accounts than one person opens at once.")
                    List<NewAccountDto> accounts) {

        List<OnboardingService.NewAccount> toNewAccounts() {
            return accounts.stream()
                    .map(
                            a ->
                                    new OnboardingService.NewAccount(
                                            a.accountNumber(),
                                            a.accountType(),
                                            a.currency(),
                                            a.openingBalanceOrZero()))
                    .toList();
        }
    }

    record NewAccountDto(
            /*
             * The real number, read off the bank's records by the administrator. Digits
             * only, 10 to 16, matching the registration form — THE SAME GUESS as that one,
             * and if Zigama's real numbers are a different shape this turns real accounts
             * away. See docs/OPEN-ITEMS.md.
             */
            @NotBlank(message = "Enter the account number.")
                    @Pattern(
                            regexp = "\\d{10,16}",
                            message = "An account number is 10 to 16 digits.")
                    String accountNumber,
            @NotNull(message = "Choose an account type.") AccountType accountType,
            /*
             * ISO 4217, validated by shape rather than against a list of currencies the
             * bank deals in — that list is the bank's to give, and a hardcoded guess here
             * would refuse a real customer at the counter.
             */
            @NotBlank(message = "Choose a currency.")
                    @Pattern(
                            regexp = "[A-Za-z]{3}",
                            message = "A currency is a three-letter code, such as RWF.")
                    String currency,
            /*
             * The balance the administrator read off the bank's records.
             *
             * A STRING, not a double and not a BigDecimal on the wire. JSON numbers go
             * through a binary floating-point type in most clients on the way here, and
             * "1234.30" arriving as 1234.2999999999999 is a rounding decision nobody made.
             * The text is parsed exactly by Money.parse.
             *
             * THIS PATTERN IS A SHAPE GUARD, NOT THE PRECISION RULE. It admits up to two
             * decimals so the message is a friendly one for an obvious typo; whether THIS
             * currency allows any decimals at all is decided by Money.parse against the
             * account's own currency — RWF has no minor unit, so "1500.50" is refused
             * there even though it passes here. One authority for scale, and it is the
             * currency.
             *
             * Optional: an empty box means an account opened at zero, which is ordinary.
             */
            @Pattern(
                            regexp = "|\\d{1,15}(\\.\\d{1,2})?",
                            message = "Enter an opening balance such as 1500 or 1500.75.")
                    String openingBalance) {

        /** An omitted or empty box is an account that starts at nothing. */
        String openingBalanceOrZero() {
            return openingBalance == null || openingBalance.isBlank() ? "0" : openingBalance.trim();
        }
    }

    /** An account as STAFF see it. NO FULL NUMBER — only the mask. */
    record CustomerAccountView(
            String id,
            String maskedNumber,
            String accountType,
            String currency,
            String assignedBy,
            String assignedAt) {

        static CustomerAccountView of(CustomerAccountEntity e) {
            return new CustomerAccountView(
                    e.id().toString(),
                    e.maskedNumber(),
                    e.accountType().name(),
                    e.currency(),
                    e.assignedBy(),
                    e.assignedAt().toString());
        }
    }

    /* ------------------------------------------------------- the mailbox */

    /**
     * What the bank has sent, or tried to.
     *
     * <p>Staff credentials required. These messages carry customers' names and addresses,
     * and a verification body contains a live code while it lasts — an unauthenticated
     * version of this endpoint would be a way to read other people's codes, which would
     * defeat the entire verification step.
     *
     * <p>It exists because there is no mail server in development: this is how the flow is
     * walked without a mailbox. {@code delivered} says whether the message actually left
     * the building, which is not the same as having been composed.
     */
    @GetMapping("/outbox")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    List<OutboxMessage> outbox() {
        return onboarding.recentMessages().stream().map(OutboxMessage::of).toList();
    }

    record OutboxMessage(
            String id,
            String kind,
            String from,
            String to,
            String subject,
            String body,
            String sentAt,
            boolean delivered,
            String failure) {

        static OutboxMessage of(OutboxEntity m) {
            return new OutboxMessage(
                    m.id().toString(),
                    m.kind().name(),
                    m.sender(),
                    m.recipient(),
                    m.subject(),
                    m.body(),
                    m.sentAt().toString(),
                    m.delivered(),
                    m.failure());
        }
    }

    /* ------------------------------------------------ the overview counts */

    /**
     * The five figures on the staff overview.
     *
     * <p>Counted in the database rather than by fetching every row and counting in Java.
     * With a handful of applications the difference is nothing; with a bank's worth it is
     * the difference between a dashboard and an outage, and the query that gets written
     * first is the one that stays.
     *
     * <p>Deliberately just counts. A staff landing page has no business carrying customer
     * records it does not display, and the screens that do show them ask for them.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    OnboardingService.StaffSummary summary() {
        return onboarding.summary();
    }

    /* ---------------------------------------------- manager only: release */

    /** The manager's step. This is the call that emails the customer. */
    @PostMapping("/customers/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    CustomerSummary approve(
            @PathVariable UUID id, Authentication caller) {
        return summaryOf(onboarding.approve(id, staff.require(caller.getName())));
    }

    /**
     * Freezing. Manager only, and deliberately NOT four-eyes — see
     * {@code OnboardingService.freeze}: making a protective action wait for a second
     * person costs the minutes that matter when a login is believed compromised.
     */
    @PostMapping("/customers/{id}/freeze")
    @PreAuthorize("hasRole('MANAGER')")
    CustomerSummary freeze(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request,
            Authentication caller) {
        return summaryOf(
                onboarding.freeze(id, request.reason(), staff.require(caller.getName())));
    }

    @PostMapping("/customers/{id}/unfreeze")
    @PreAuthorize("hasRole('MANAGER')")
    CustomerSummary unfreeze(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request,
            Authentication caller) {
        return summaryOf(
                onboarding.unfreeze(id, request.reason(), staff.require(caller.getName())));
    }

    /* --------------------------------------------- manager: transfers */

    /**
     * A transfer waiting on a decision, as the queue screen reads it.
     *
     * <p>MASKS AND NAMES, never account numbers. This is a staff screen and it is behind
     * staff authentication, but the queue is the densest page in the portal — a full
     * account number here is a full account number in every screenshot of it.
     *
     * <p>The amount travels as a decimal STRING with its currency beside it, like every
     * other amount in this API. A manager approving a payment must see the figure the
     * ledger holds, not a number a JSON parser has rounded.
     */
    record TransferQueueItem(
            String id,
            String sourceMask,
            String destinationMask,
            String amount,
            String currency,
            String reference,
            String submittedAt) {}

    /**
     * Everything waiting on a manager, oldest first.
     *
     * <p>Oldest first because a queue is worked from the front: newest-first leaves the
     * transfer somebody has been waiting on longest at the bottom of the list, which is
     * the one most likely to be telephoned about.
     */
    @GetMapping("/transfers")
    @PreAuthorize("hasRole('MANAGER')")
    List<TransferQueueItem> transferQueue() {
        return transfers.queue().stream()
                .map(
                        transfer ->
                                new TransferQueueItem(
                                        transfer.id().toString(),
                                        maskOf(transfer.sourceAccountId()),
                                        maskOf(transfer.destinationAccountId()),
                                        transfer.amount().toPlainString(),
                                        transfer.amount().currency(),
                                        transfer.reference(),
                                        transfer.submittedAt().toString()))
                .toList();
    }

    /**
     * Releases a transfer. The beneficiary is credited in the same transaction.
     *
     * <p>MANAGER ONLY, and the customer who submitted it is not staff, so there is no
     * self-approval to prevent here — the four-eyes rule this endpoint carries is that a
     * customer's instruction cannot move money to somebody else without a member of the
     * bank's staff putting their name to it.
     */
    @PostMapping("/transfers/{id}/approve")
    @PreAuthorize("hasRole('MANAGER')")
    TransferQueueItem approveTransfer(@PathVariable UUID id, Authentication caller) {
        var approved = transfers.approve(id, staff.require(caller.getName()));
        return new TransferQueueItem(
                approved.id().toString(),
                maskOf(approved.sourceAccountId()),
                maskOf(approved.destinationAccountId()),
                approved.amount().toPlainString(),
                approved.amount().currency(),
                approved.reference(),
                approved.submittedAt().toString());
    }

    /**
     * Refuses a transfer and returns the money to the sender.
     *
     * <p>A reason is required, and it is the only thing the customer will be told. A
     * refused payment with no stated reason is one nobody at the bank can explain to the
     * person who is about to telephone about it.
     */
    @PostMapping("/transfers/{id}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    TransferQueueItem rejectTransfer(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request,
            Authentication caller) {

        var rejected = transfers.reject(id, request.reason(), staff.require(caller.getName()));
        return new TransferQueueItem(
                rejected.id().toString(),
                maskOf(rejected.sourceAccountId()),
                maskOf(rejected.destinationAccountId()),
                rejected.amount().toPlainString(),
                rejected.amount().currency(),
                rejected.reference(),
                rejected.submittedAt().toString());
    }

    /**
     * The mask for an account id, or a placeholder.
     *
     * <p>A transfer's accounts are foreign keys and cannot vanish, so the fallback is
     * unreachable — it is here so that a queue screen degrades to one unreadable row
     * rather than failing to render the manager's whole list.
     */
    private String maskOf(UUID accountId) {
        return customerAccounts
                .findById(accountId)
                .map(rw.bank.ibanking.onboarding.domain.CustomerAccountEntity::maskedNumber)
                .orElse("(account not found)");
    }

    /* ------------------------------------------ correcting an account */

    /**
     * Takes an account off a customer's profile.
     *
     * <p>The accounts are entered by the administrator when the login is created; this is
     * the correction path for the case that matters — a wrong number was typed and
     * somebody is seeing an account that is not theirs.
     *
     * <p>MANAGER ONLY, and a reason is required. Removing access to an account is the
     * mirror of granting it, and if a mistake like that has been made, who made it and who
     * caught it is the first thing anyone will ask.
     */
    @PostMapping("/customers/{id}/accounts/{accountId}/remove")
    @PreAuthorize("hasRole('MANAGER')")
    CustomerSummary removeAccount(
            @PathVariable UUID id,
            @PathVariable UUID accountId,
            @Valid @RequestBody ReasonRequest request,
            Authentication caller) {

        onboarding.removeAccount(id, accountId, request.reason(), staff.require(caller.getName()));

        /*
         * The whole customer back, not a fragment. The screen's accounts column is derived
         * from this, and leaving the caller to merge a fragment is how a list goes stale on
         * screen while being correct on the server.
         */
        return summaryOf(onboarding.customer(id));
    }

    /* ------------------------------------- manager: forgotten passwords */

    /**
     * A customer waiting for a new password, as the queue screen reads it.
     *
     * <p>CARRIES NO CREDENTIAL and never will: the password is created when a manager
     * presses the button, exists in one local variable and in the email composed from it,
     * and no endpoint in this API returns one.
     *
     * <p>It DOES carry the account's status, because that is what the manager has to look
     * at before issuing anything. A frozen customer cannot sign in whatever password they
     * are given, and the service refuses to issue one — showing the status here is what
     * stops the manager finding that out by pressing the button.
     */
    record PasswordRequestItem(
            String id,
            String customerId,
            String customerNumber,
            String fullName,
            String email,
            String status,
            String requestedAt) {}

    @GetMapping("/password-requests")
    @PreAuthorize("hasRole('MANAGER')")
    List<PasswordRequestItem> passwordRequests() {
        return passwordResets.waiting().stream()
                .map(
                        request -> {
                            var customer = passwordResets.customerFor(request);
                            return new PasswordRequestItem(
                                    request.id().toString(),
                                    customer.id().toString(),
                                    customer.customerNumber(),
                                    customer.fullName(),
                                    customer.email(),
                                    customer.status().name(),
                                    request.requestedAt().toString());
                        })
                .toList();
    }

    /**
     * Issues a new temporary password and emails it.
     *
     * <p>MANAGER ONLY, matching approval rather than account creation. Both this and
     * approval hand somebody a working credential for an account; creating an account
     * hands out nothing until a manager releases it. The role that can give access is the
     * role that can give it back.
     */
    @PostMapping("/password-requests/{id}/issue")
    @PreAuthorize("hasRole('MANAGER')")
    PasswordRequestItem issuePassword(@PathVariable UUID id, Authentication caller) {
        var request = passwordResets.fulfil(id, staff.require(caller.getName()));
        var customer = passwordResets.customerFor(request);
        return new PasswordRequestItem(
                request.id().toString(),
                customer.id().toString(),
                customer.customerNumber(),
                customer.fullName(),
                customer.email(),
                customer.status().name(),
                request.requestedAt().toString());
    }

    /** Refuses the request. The reason is what the customer is told, so it is required. */
    @PostMapping("/password-requests/{id}/refuse")
    @PreAuthorize("hasRole('MANAGER')")
    PasswordRequestItem refusePassword(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request,
            Authentication caller) {

        var refused =
                passwordResets.refuse(id, request.reason(), staff.require(caller.getName()));
        var customer = passwordResets.customerFor(refused);
        return new PasswordRequestItem(
                refused.id().toString(),
                customer.id().toString(),
                customer.customerNumber(),
                customer.fullName(),
                customer.email(),
                customer.status().name(),
                refused.requestedAt().toString());
    }

    record ReasonRequest(
            @NotBlank(message = "Give a reason, so whoever reviews this later knows why.")
                    @Size(max = 500)
                    String reason) {}

    @PostMapping("/customers/{id}/reject")
    @PreAuthorize("hasRole('MANAGER')")
    CustomerSummary reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectRequest request,
            Authentication caller) {
        return summaryOf(
                onboarding.reject(id, request.reason(), staff.require(caller.getName())));
    }

    /* ------------------------------------------- payees waiting to be checked */

    /**
     * A saved payee waiting for a member of staff, with what they need to decide.
     *
     * <p>THE TWO NAMES ARE THE WHOLE POINT. {@code name} is what the CUSTOMER typed and is
     * evidence of nothing — it is free text. {@code heldName} is who the bank holds that
     * account for, resolved now. Shown together with {@code nameCheck}, the reviewer's job
     * is a comparison; shown alone, as the first draft of this screen did, there is nothing
     * to compare and approving is a reflex.
     *
     * <p>{@code heldName} IS NULL WHERE THERE IS NOTHING TO SHOW — a payee at another
     * institution, or an account number this bank does not hold — and {@code nameCheck} is
     * then {@code UNAVAILABLE} rather than a mismatch. The screen must say which, because a
     * blank where a comparison belongs reads as a pass.
     *
     * <p>NO FULL ACCOUNT NUMBER, here as everywhere. The mask is what identifies the payee
     * on screen, and a staff screen is not a reason to relax the rule — it is a screen in
     * an open-plan office with somebody standing behind it.
     */
    record BeneficiaryReviewItem(
            String id,
            String customerId,
            String customerNumber,
            String customerName,
            String name,
            String heldName,
            String nameCheck,
            String beneficiaryType,
            String provider,
            String maskedDestination,
            String currency,
            String addedAt,
            String status) {

        static BeneficiaryReviewItem of(BeneficiaryService.Review review) {
            var payee = review.beneficiary();
            return new BeneficiaryReviewItem(
                    payee.id().toString(),
                    review.customer().id().toString(),
                    review.customer().customerNumber(),
                    review.customer().fullName(),
                    payee.name(),
                    review.heldName(),
                    review.nameCheck().name(),
                    payee.beneficiaryType().name(),
                    payee.provider(),
                    payee.maskedDestination(),
                    payee.currency(),
                    payee.addedAt().toString(),
                    payee.status().name());
        }
    }

    /**
     * Everything waiting, oldest first.
     *
     * <p>EITHER ROLE, unlike the password queue above. The four-eyes rule that keeps ADMIN
     * and MANAGER disjoint is about CREATING an account and RELEASING it — issuing a
     * credential takes two people. Here the maker is the CUSTOMER and bank staff are the
     * checker, so the separation holds whichever role clears it. Payee review is also
     * volume work, and queueing it behind the one role that releases money would mean
     * customers waiting on a manager to do data entry.
     */
    @GetMapping("/beneficiaries")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    List<BeneficiaryReviewItem> pendingBeneficiaries() {
        return beneficiaries.waiting().stream().map(BeneficiaryReviewItem::of).toList();
    }

    /** Clears the payee for payment and emails the customer. */
    @PostMapping("/beneficiaries/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    BeneficiaryReviewItem approveBeneficiary(@PathVariable UUID id, Authentication caller) {
        var approved = beneficiaries.approve(id, staff.require(caller.getName()));
        return BeneficiaryReviewItem.of(beneficiaries.review(approved));
    }

    /**
     * Declines the payee. The reason is what the customer is shown and emailed, so it is
     * required — {@link ReasonRequest} carries the validation and the message.
     */
    @PostMapping("/beneficiaries/{id}/refuse")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    BeneficiaryReviewItem refuseBeneficiary(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request,
            Authentication caller) {

        var refused =
                beneficiaries.refuse(id, request.reason(), staff.require(caller.getName()));
        return BeneficiaryReviewItem.of(beneficiaries.review(refused));
    }

    /* ------------------------------- cards and cheque books waiting to be made */

    /**
     * A card or cheque book request waiting for somebody at the bank.
     *
     * <p>NO COLLECTION POINT ON A SUBMITTED ONE, because there is not one yet — the member
     * of staff who produces the thing types where it ended up. The portal has no branch
     * list of its own, and the screens that used to name one were naming branches the
     * front end had invented.
     *
     * <p>Carries the ACCOUNT MASK rather than the account id, for the same reason every
     * other staff screen does: this is a dense screen in an open-plan office.
     */
    record ServiceRequestItem(
            String id,
            String reference,
            String customerId,
            String customerNumber,
            String customerName,
            String email,
            String requestType,
            String details,
            String accountMask,
            String status,
            String submittedAt,
            String collectionPoint) {

        static ServiceRequestItem of(ServiceRequestService.Queued queued) {
            var request = queued.request();
            return new ServiceRequestItem(
                    request.id().toString(),
                    request.reference(),
                    queued.customer().id().toString(),
                    queued.customer().customerNumber(),
                    queued.customer().fullName(),
                    queued.customer().email(),
                    request.requestType().name(),
                    request.details(),
                    queued.accountMask(),
                    request.status().name(),
                    request.submittedAt().toString(),
                    request.collectionPoint());
        }
    }

    /**
     * Everything still open, oldest first.
     *
     * <p>EITHER ROLE, like the payee queue and unlike the password queue. The four-eyes
     * split between ADMIN and MANAGER is about creating an account and releasing it;
     * here the maker is the CUSTOMER and staff are the checker, so it does not apply —
     * and printing a card is volume work that should not queue behind the one role that
     * releases money.
     */
    @GetMapping("/service-requests")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    List<ServiceRequestItem> openServiceRequests() {
        return serviceRequests.open().stream().map(ServiceRequestItem::of).toList();
    }

    /**
     * Marks it produced and emails the customer where to collect it.
     *
     * <p>The collection point is REQUIRED. It is the one fact in this flow that only the
     * bank has, and the email that goes out is the only place the product states it.
     */
    @PostMapping("/service-requests/{id}/ready")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    ServiceRequestItem markServiceRequestReady(
            @PathVariable UUID id,
            @Valid @RequestBody CollectionPointRequest request,
            Authentication caller) {

        var ready =
                serviceRequests.markReady(
                        id, request.collectionPoint(), staff.require(caller.getName()));
        return ServiceRequestItem.of(serviceRequests.queued(ready));
    }

    record CollectionPointRequest(
            @NotBlank(
                            message =
                                    "Say where the customer should collect it. The portal has no"
                                            + " branch list of its own and must not guess one.")
                    @Size(max = 120)
                    String collectionPoint) {}

    /** Records that it was handed over. No email: the customer is at the counter. */
    @PostMapping("/service-requests/{id}/collected")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    ServiceRequestItem markServiceRequestCollected(
            @PathVariable UUID id, Authentication caller) {

        var collected = serviceRequests.markCollected(id, staff.require(caller.getName()));
        return ServiceRequestItem.of(serviceRequests.queued(collected));
    }

    /** Declines it. The reason is shown and emailed to the customer, so it is required. */
    @PostMapping("/service-requests/{id}/decline")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    ServiceRequestItem declineServiceRequest(
            @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request,
            Authentication caller) {

        var declined =
                serviceRequests.decline(id, request.reason(), staff.require(caller.getName()));
        return ServiceRequestItem.of(serviceRequests.queued(declined));
    }
}
