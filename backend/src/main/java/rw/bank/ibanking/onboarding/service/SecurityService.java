package rw.bank.ibanking.onboarding.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.SignInEntity;
import rw.bank.ibanking.onboarding.domain.TrustedDeviceEntity;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.SignInRepository;

/**
 * WHAT A CUSTOMER CAN SEE AND DO ABOUT THEIR OWN ACCESS.
 *
 * <p>WHAT THIS REPLACED. The portal had a complete security page — trusted browsers with a
 * revoke button, a sign-in history, and a change-password form — calling {@code
 * /security/devices}, {@code /security/events} and {@code /security/password}. None of the
 * three existed on the server. Every one was answered by MSW from arrays that start empty
 * on each page load, so the history showed nothing whatever had happened to the account,
 * and the revoke button ended trust in a browser that was never trusted.
 *
 * <p>THE SIGN-IN HISTORY IS A SECURITY CONTROL, AND IT WAS LYING. The rows it would have
 * shown carried a location, and all four call sites that wrote one passed the literal
 * {@code "Kigali, Rwanda"} — see {@link rw.bank.ibanking.onboarding.domain.SignInMethod}.
 * V19 drops the column and records the sign-in method and a coarse browser description
 * instead, both of which the service knows for certain. This class only ever reports what
 * is in the table; there is no fallback and nothing composed to fill a gap.
 *
 * <p>NO SEPARATE AUDIT OF FAILURES. The screen's old type had an {@code outcome} of SUCCESS
 * or FAILURE and nothing ever wrote FAILURE: {@code sign_ins} records sign-ins that
 * happened. Showing failed attempts would be worth doing and is not invented here — it
 * needs its own table, a decision about how long to keep attempts against an address that
 * may not be a customer's, and a rate-limit story. docs/OPEN-ITEMS.md records it.
 */
@Service
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    /**
     * How much history a customer is shown.
     *
     * <p>Twenty, and bounded in the QUERY rather than after loading everything — the same
     * point PasswordResetRequestRepository makes about its rate limit. A customer of five
     * years has thousands of rows and no interest in the oldest of them; what this list is
     * for is "is there anything here I do not recognise", which is answered by the recent
     * end or not at all.
     */
    private static final int HISTORY_SHOWN = 20;

    private final SignInRepository signIns;
    private final TrustedDevices trustedDevices;
    private final CustomerRepository customers;
    private final PasswordEncoder passwords;
    private final Mailer mailer;
    private final Notifications notifications;

    SecurityService(
            SignInRepository signIns,
            TrustedDevices trustedDevices,
            CustomerRepository customers,
            PasswordEncoder passwords,
            Mailer mailer,
            Notifications notifications) {
        this.signIns = signIns;
        this.trustedDevices = trustedDevices;
        this.customers = customers;
        this.passwords = passwords;
        this.mailer = mailer;
        this.notifications = notifications;
    }

    /** The customer's own browsers, newest first, revoked ones included. */
    @Transactional(readOnly = true)
    public List<TrustedDeviceEntity> devices(UUID customerId) {
        return trustedDevices.listFor(customerId);
    }

    /** Whether the device with this id is the browser the request came from. */
    @Transactional(readOnly = true)
    public boolean isCurrentDevice(UUID deviceId, String deviceCookie) {
        return trustedDevices.isCurrent(deviceId, deviceCookie);
    }

    /**
     * Ends trust in one browser at the customer's request.
     *
     * <p>REFUSES THE BROWSER THE REQUEST CAME FROM. Allowing it would work and would then
     * require an emailed code from a machine the customer has just told us they no longer
     * control — the commonest reason to be on this screen is a lost or stolen device, and
     * the second commonest is a mis-click. Everything else is revocable; "revoke all the
     * others" is the action that makes sense here, and the screen offers it.
     */
    @Transactional
    public TrustedDeviceEntity revokeDevice(UUID customerId, UUID deviceId, String deviceCookie) {
        if (trustedDevices.isCurrent(deviceId, deviceCookie)) {
            throw new BusinessRuleException(
                    "This is the browser you are using now, so it cannot be removed from here."
                            + " Sign out to end this session, or remove your other browsers.");
        }

        return trustedDevices.revokeOne(customerId, deviceId, "Removed by the customer");
    }

    /** The customer's recent sign-ins, newest first. */
    @Transactional(readOnly = true)
    public List<SignInEntity> history(UUID customerId) {
        return signIns.findByCustomerIdOrderBySignedInAtDesc(customerId, Limit.of(HISTORY_SHOWN));
    }

    /**
     * Changes the password of a customer who knows their current one.
     *
     * <p>NOT THE SAME PATH AS {@code changeTemporaryPassword}, and the difference is the
     * reason this method exists rather than reusing it. That one runs on a session that can
     * do nothing else, on a credential bank staff already know, and the customer is being
     * let in for the first time. This one runs on a live session and is the thing somebody
     * does when they think their password is known to someone else.
     *
     * <p>SO IT REVOKES EVERY TRUSTED BROWSER, which is the whole point and is easy to
     * leave out. A customer changing their password because they fear somebody has it has
     * not finished the job if every browser that person trusted still signs in on the new
     * password without a code — the password was the only thing they could change, and the
     * remembered browsers would silently survive it. {@code PasswordResetService} already
     * does this on a reset; a voluntary change is the same event with the same reasoning.
     *
     * @return how many browsers were revoked, so the screen can tell the customer what
     *     else just happened to them. Saying nothing would mean being asked for a code on
     *     the next sign-in with no explanation.
     */
    @Transactional
    public int changePassword(UUID customerId, String currentPassword, String newPassword) {
        CustomerEntity customer =
                customers
                        .findById(customerId)
                        .orElseThrow(
                                () ->
                                        new CustomerAuthService.UnauthenticatedException(
                                                "You have been signed out. Please sign in"
                                                        + " again."));

        /*
         * THE CURRENT PASSWORD IS CHECKED FIRST, and a wrong one ends it here. Without
         * this, a session left open on a shared machine is a session that can lock its
         * owner out — and the person doing it would not need to know the password.
         */
        if (!passwords.matches(currentPassword, customer.passwordHash())) {
            throw new BusinessRuleException(
                    "That is not your current password. Check it and type it again.");
        }

        /* The same policy the temporary-password change applies, from the same method, so
           the two screens cannot come to disagree about what a password must be. */
        String problem = CustomerAuthService.passwordProblem(newPassword);
        if (problem != null) {
            throw new BusinessRuleException(problem);
        }

        if (passwords.matches(newPassword, customer.passwordHash())) {
            throw new BusinessRuleException(
                    "Choose a password that is different from your current one.");
        }

        customer.replacePassword(passwords.encode(newPassword));
        customers.save(customer);

        int revoked =
                trustedDevices.revokeAll(
                        customer.id(),
                        "The customer changed their password");

        /*
         * TOLD, NOT ASKED. The email cannot stop the change — the person making it already
         * had the current password — so it is a notification and goes out after the fact.
         * It is the one way a customer whose password was changed BY SOMEBODY ELSE finds
         * out, which is why it is sent in the same transaction: a change with no email is a
         * change nobody is told about.
         */
        mailer.send(
                OutboxKind.PASSWORD_CHANGED,
                customer.email(),
                EmailTemplates.PASSWORD_CHANGED_SUBJECT,
                EmailTemplates.passwordChanged(customer.fullName(), revoked));

        /*
         * AND IN THE PORTAL, as a SECURITY notification, which the panel pins above
         * everything else. Somebody who changed the password is signed in and will see it;
         * somebody whose password was changed FOR them meets it at the top of the list on
         * their next sign-in, which is the case this row exists for.
         */
        notifications.passwordChanged(customer.id(), revoked);

        log.info(
                "Customer {} changed their password ({} trusted browser(s) revoked)",
                customer.customerNumber(),
                revoked);

        return revoked;
    }
}
