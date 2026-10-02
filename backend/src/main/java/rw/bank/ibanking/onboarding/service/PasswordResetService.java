package rw.bank.ibanking.onboarding.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.RateLimitedException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.PasswordResetRequestEntity;
import rw.bank.ibanking.onboarding.domain.PasswordResetStatus;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.PasswordResetRequestRepository;

/**
 * A FORGOTTEN PASSWORD, from the customer asking to a manager handing one out.
 *
 * <p>There is no self-service reset here: no token in an email, no page that sets a new
 * password from a link. The customer asks, a manager sees the request, and the manager
 * re-issues a temporary password through the SAME path used at approval — emailed,
 * expiring, and refused for everything except the change-password screen until it has been
 * replaced. V15 records why that shape was chosen over a reset link.
 *
 * <p>WHAT THIS CLASS IS CAREFUL ABOUT, in one place, because each of these is a way a
 * password-reset feature leaks:
 *
 * <ul>
 *   <li>THE ANSWER IS THE SAME for an address that banks here and one that does not. The
 *       request method returns nothing at all, so the controller has nothing to vary.
 *   <li>NOTHING IS WRITTEN for an unknown address. A table of everything typed into the
 *       form is a list of addresses that are not customers, and a queue showing staff
 *       "somebody tried bob@example.com" tells them — and anybody near their screen — the
 *       same thing.
 *   <li>NO CREDENTIAL IS CREATED BY ASKING. The customer's existing password keeps working
 *       until a manager acts, so a stranger submitting somebody's address cannot even lock
 *       them out.
 *   <li>ISSUING ONE REVOKES THE TRUSTED BROWSERS. A reset is the bank's response to
 *       "somebody may have my password", and a browser still allowed to skip the emailed
 *       code would be the one part of that access the reset had not touched.
 * </ul>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /**
     * How many requests one customer may raise in {@link #RATE_WINDOW}.
     *
     * <p>Low, because the duplicate guard below already caps a waiting customer at one open
     * request: this only bites on somebody asking again after each one is settled. Three in
     * an hour is a person having a bad morning; the fourth is a script.
     */
    private static final int MAX_REQUESTS_PER_WINDOW = 3;

    private static final Duration RATE_WINDOW = Duration.ofHours(1);

    private final CustomerRepository customers;
    private final PasswordResetRequestRepository requests;
    private final PasswordEncoder passwords;
    private final Mailer mailer;
    private final TrustedDevices trustedDevices;

    PasswordResetService(
            CustomerRepository customers,
            PasswordResetRequestRepository requests,
            PasswordEncoder passwords,
            Mailer mailer,
            TrustedDevices trustedDevices) {
        this.customers = customers;
        this.requests = requests;
        this.passwords = passwords;
        this.mailer = mailer;
        this.trustedDevices = trustedDevices;
    }

    /* ======================================================= the customer asks */

    /**
     * Records that whoever owns this address wants a new password.
     *
     * <p>RETURNS NOTHING, AND THAT IS THE SECURITY PROPERTY. A method that returned
     * "recorded" or "no such customer" would be an account-enumeration oracle the moment
     * somebody put it in a response, and the next person to touch the controller would not
     * know that the return value was the dangerous part. There is nothing to leak because
     * there is nothing to return.
     *
     * <p>THE RATE LIMIT ONLY COUNTS REAL CUSTOMERS, which is worth stating rather than
     * leaving as a gap somebody finds later. An unknown address writes no row, so there is
     * nothing to count and the work done is one indexed lookup — no email is sent and no
     * state changes, so hammering the endpoint with guesses achieves nothing beyond load,
     * which belongs to the gateway rather than here. It IS recorded in
     * frontend/docs/OPEN-ITEMS.md as something a public deployment should also throttle by
     * address.
     */
    @Transactional
    public void request(String identifier) {
        String email = normalise(identifier);
        if (email.isEmpty()) return;

        Optional<CustomerEntity> found = customers.findByEmailIgnoreCase(email);
        if (found.isEmpty()) {
            /*
             * Logged WITHOUT the address. "Somebody asked to reset a password for an
             * address we do not hold" is useful when a log is being read after an incident;
             * the address itself would turn the log into the enumeration list this method
             * refuses to build.
             */
            log.info("A password reset was asked for an address that is not a customer's");
            return;
        }

        CustomerEntity customer = found.get();

        /*
         * ALREADY WAITING: nothing more to do, and deliberately not an error. The customer
         * clicking twice has not done anything wrong, and the second click must look
         * exactly like the first from outside — an error here would say "yes, this address
         * banks here AND has a request open".
         */
        Optional<PasswordResetRequestEntity> open =
                requests.findFirstByCustomerIdAndStatus(customer.id(), PasswordResetStatus.PENDING);
        if (open.isPresent()) {
            log.info(
                    "Customer {} asked again for a password reset; request {} is already waiting",
                    customer.customerNumber(),
                    open.get().id());
            return;
        }

        enforceRateLimit(customer);

        PasswordResetRequestEntity raised = PasswordResetRequestEntity.raisedBy(customer.id());
        requests.save(raised);

        log.info(
                "Customer {} asked for a new password; request {} is waiting for a manager",
                customer.customerNumber(),
                raised.id());
    }

    /**
     * Counted on the customer's own requests, not on the address, for the reason given on
     * {@link #request}.
     *
     * <p>Throws {@link RateLimitedException} rather than returning quietly, because this
     * only ever fires for a REAL customer who has asked three times in an hour: they are
     * already known to the system, so the message tells them something true and useful
     * instead of leaving them clicking.
     */
    private void enforceRateLimit(CustomerEntity customer) {
        long recent =
                requests.countByCustomerIdAndRequestedAtAfter(
                        customer.id(), Instant.now().minus(RATE_WINDOW));

        if (recent >= MAX_REQUESTS_PER_WINDOW) {
            throw new RateLimitedException(
                    "You have asked for a new password several times in the last hour. Please wait"
                            + " a little, or call the number on the back of your card.",
                    RATE_WINDOW);
        }
    }

    /* ====================================================== the manager answers */

    /** Everything still waiting, oldest first. */
    @Transactional(readOnly = true)
    public List<PasswordResetRequestEntity> waiting() {
        return requests.findByStatusOrderByRequestedAtAsc(PasswordResetStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public long waitingCount() {
        return requests.countByStatus(PasswordResetStatus.PENDING);
    }

    /** The customer behind a request, for the queue screen. */
    @Transactional(readOnly = true)
    public CustomerEntity customerFor(PasswordResetRequestEntity request) {
        return customers
                .findById(request.customerId())
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find the customer this request belongs to."));
    }

    /**
     * Issues a new temporary password and emails it.
     *
     * <p>Everything here happens in one transaction on purpose. A password written to the
     * customer row with no email sent is a customer locked out of an account whose password
     * only the database knows; an email sent with no password written is a credential in an
     * inbox that does not work. Both are worse than the request staying in the queue.
     */
    @Transactional
    public PasswordResetRequestEntity fulfil(UUID requestId, StaffEntity manager) {
        PasswordResetRequestEntity request = findRequest(requestId);
        CustomerEntity customer = customerFor(request);

        /*
         * A FROZEN OR UNAPPROVED CUSTOMER GETS NO PASSWORD. Issuing one would be work that
         * cannot help: sign-in is refused for them on status anyway, so the customer would
         * receive a credential, try it, and be told their details do not match. Whatever is
         * actually wrong with the account has to be dealt with first, and the manager is
         * the person who can see what that is.
         */
        if (!customer.canSignIn()) {
            throw new BusinessRuleException(
                    "This customer cannot sign in at the moment, so a new password would not help"
                            + " them. Deal with the account's status first, then issue one.");
        }

        /*
         * The credential exists HERE and nowhere else in readable form — in this local
         * variable and in the message composed from it. Never logged, never returned.
         */
        String temporaryPassword = TemporaryPasswords.generate();
        customer.issueTemporaryPassword(
                passwords.encode(temporaryPassword),
                Instant.now().plus(TemporaryPasswords.VALIDITY));
        customers.save(customer);

        /*
         * THE REMEMBERED BROWSERS GO. A reset is what the bank does when somebody may have
         * a customer's password; a browser still entitled to skip the emailed code would be
         * the one door the reset left open. The customer is told in the email, because
         * otherwise being asked for a code again looks like a fault.
         */
        int revoked =
                trustedDevices.revokeAll(
                        customer.id(), "Password re-issued after the customer asked for a reset");

        request.fulfilled(manager.fullName());
        requests.save(request);

        mailer.send(
                OutboxKind.PASSWORD_REISSUED,
                customer.email(),
                EmailTemplates.RESET_ISSUED_SUBJECT,
                EmailTemplates.resetIssued(
                        customer.fullName(),
                        customer.email(),
                        temporaryPassword,
                        TemporaryPasswords.VALIDITY));

        log.info(
                "A new temporary password was issued to customer {} by {} (request {},"
                        + " {} trusted browser(s) revoked)",
                customer.customerNumber(),
                manager.email(),
                request.id(),
                revoked);

        return request;
    }

    /** Refuses the request, with the reason the customer is told. */
    @Transactional
    public PasswordResetRequestEntity refuse(UUID requestId, String reason, StaffEntity manager) {
        PasswordResetRequestEntity request = findRequest(requestId);
        CustomerEntity customer = customerFor(request);

        // Throws if the reason is missing, or if another manager got here first.
        request.refused(manager.fullName(), reason);
        requests.save(request);

        mailer.send(
                OutboxKind.PASSWORD_REQUEST_REFUSED,
                customer.email(),
                EmailTemplates.RESET_REFUSED_SUBJECT,
                EmailTemplates.resetRefused(customer.fullName(), request.refusedReason()));

        log.info(
                "A password request from customer {} was refused by {} (request {})",
                customer.customerNumber(),
                manager.email(),
                request.id());

        return request;
    }

    private PasswordResetRequestEntity findRequest(UUID requestId) {
        return requests
                .findById(requestId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find that password request. It may already"
                                                + " have been dealt with."));
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
