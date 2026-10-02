package rw.bank.ibanking.onboarding.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.repo.AccountTransactionRepository;
import rw.bank.ibanking.onboarding.repo.ApplicationRepository;
import rw.bank.ibanking.onboarding.repo.BusinessApplicationRepository;
import rw.bank.ibanking.onboarding.repo.BusinessDocumentRepository;
import rw.bank.ibanking.onboarding.repo.BusinessSignatoryRepository;
import rw.bank.ibanking.onboarding.repo.CompanyRepository;
import rw.bank.ibanking.onboarding.repo.CorporateMembershipRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.EmailVerificationRepository;
import rw.bank.ibanking.onboarding.repo.LoginChallengeRepository;
import rw.bank.ibanking.onboarding.repo.OutboxRepository;
import rw.bank.ibanking.onboarding.repo.SignInRepository;
import rw.bank.ibanking.onboarding.repo.TransferRepository;

/**
 * Empties the onboarding tables so the flow can be walked from nothing.
 *
 * <p>DEVELOPMENT ONLY, and the guard is the point of this class existing separately.
 *
 * <p>{@code @Profile({"dev","h2","test"})} means the bean is never created under any
 * other profile, so the endpoint is not merely protected in production — it is not
 * mapped, and a request to it gets the same 404 as a path that was never written. That is
 * a stronger guarantee than a role check, because a role check is one misconfigured
 * account away from being satisfied, and "delete every customer" must not be reachable by
 * any combination of credentials on a running bank.
 *
 * <p>It lives in its own file for the same reason: a method on {@link AdminController}
 * would be one deleted annotation away from shipping, and nothing about the surrounding
 * class would remind the person deleting it.
 *
 * <p>It still requires an administrator, because a development database is not a free-for
 * -all — a colleague pointed at the same instance should not lose their work to an
 * unauthenticated call.
 */
@RestController
@RequestMapping("/api/v1/admin/dev")
@Profile({"dev", "h2", "test"})
class DevResetController {

    private static final Logger log = LoggerFactory.getLogger(DevResetController.class);

    private final AccountTransactionRepository accountTransactions;
    private final CustomerAccountRepository customerAccounts;
    private final TransferRepository transfers;
    private final CorporateMembershipRepository memberships;
    private final CompanyRepository companies;
    private final BusinessApplicationRepository businessApplications;
    private final BusinessSignatoryRepository businessSignatories;
    private final BusinessDocumentRepository businessDocuments;
    private final ApplicationRepository applications;
    private final CustomerRepository customers;
    private final EmailVerificationRepository verifications;
    private final LoginChallengeRepository challenges;
    private final SignInRepository signIns;
    private final OutboxRepository outbox;

    DevResetController(
            AccountTransactionRepository accountTransactions,
            CustomerAccountRepository customerAccounts,
            TransferRepository transfers,
            CorporateMembershipRepository memberships,
            CompanyRepository companies,
            BusinessApplicationRepository businessApplications,
            BusinessSignatoryRepository businessSignatories,
            BusinessDocumentRepository businessDocuments,
            ApplicationRepository applications,
            CustomerRepository customers,
            EmailVerificationRepository verifications,
            LoginChallengeRepository challenges,
            SignInRepository signIns,
            OutboxRepository outbox) {
        this.accountTransactions = accountTransactions;
        this.customerAccounts = customerAccounts;
        this.transfers = transfers;
        this.memberships = memberships;
        this.companies = companies;
        this.businessApplications = businessApplications;
        this.businessSignatories = businessSignatories;
        this.businessDocuments = businessDocuments;
        this.applications = applications;
        this.customers = customers;
        this.verifications = verifications;
        this.challenges = challenges;
        this.signIns = signIns;
        this.outbox = outbox;
    }

    @PostMapping("/reset")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void reset(Authentication caller) {
        /*
         * Children before parents. `customers` is referenced by `login_challenges` and
         * `sign_ins`, so deleting customers first is a referential integrity violation
         * rather than a clean slate — the same ordering the integration tests need.
         */
        challenges.deleteAll();
        signIns.deleteAll();
        // Children before parents, in the same order as the test base class.
        /*
         * The ledger before the accounts. account_transactions has a foreign key to
         * customer_accounts, so the other order leaves the delete to be refused by the
         * database — or, worse under a cascade, silently takes the entries with it and
         * makes this endpoint the one place a balance can vanish without a trace.
         */
        /*
         * Transfers before the ledger, because a transfer row points at the entries it
         * produced as well as at both accounts. Deleting the entries first leaves those
         * foreign keys dangling.
         */
        transfers.deleteAll();
        accountTransactions.deleteAll();
        /*
         * Memberships before the accounts and before the customers, and the companies
         * after both.
         *
         * corporate_memberships points at customers AND companies; customer_accounts
         * points at companies. So companies cannot go until the accounts have, and the
         * accounts cannot go until the ledger has. It cascades on PostgreSQL and does not
         * on H2, which is the whole reason this order is written out instead of trusted.
         */
        memberships.deleteAll();
        customerAccounts.deleteAll();
        customers.deleteAll();
        companies.deleteAll();
        /*
         * A business application's rows before the application they hang off.
         *
         * The foreign keys cascade in PostgreSQL, so this is belt and braces there — and
         * it is not belt and braces on H2, where the tests run, nor obvious to whoever
         * adds the next child table. The rule this file already follows is children
         * first, written down rather than delegated to a dialect.
         */
        businessDocuments.deleteAll();
        businessSignatories.deleteAll();
        businessApplications.deleteAll();
        applications.deleteAll();
        verifications.deleteAll();
        outbox.deleteAll();

        // Logged loudly. In development this is routine; in a shared database it is the
        // line somebody needs when their test data disappears.
        log.warn(
                "DEVELOPMENT RESET: every application, customer, verification, sign-in and"
                        + " message deleted, by {}",
                caller.getName());
    }
}
