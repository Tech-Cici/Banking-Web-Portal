package rw.bank.ibanking.onboarding.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.config.OutboundMailProperties;
import rw.bank.ibanking.config.SignInProperties;
import rw.bank.ibanking.exception.ApiException;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.RateLimitedException;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.CustomerStatus;
import rw.bank.ibanking.onboarding.domain.LoginChallengeEntity;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.SignInEntity;
import rw.bank.ibanking.onboarding.domain.SignInMethod;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.LoginChallengeRepository;
import rw.bank.ibanking.onboarding.repo.SignInRepository;

/**
 * Customer sign-in.
 *
 * <p>Three steps, and the middle one is the point: a password gets you a challenge, not a
 * session. Building this as a single call and adding the second factor later means
 * rewriting it, and worse, means shipping a period during which a stolen password is
 * enough.
 *
 * <p>The outcomes are deliberately distinct. {@code PASSWORD_CHANGE_REQUIRED} exists
 * because a customer signing in with the password an administrator issued is in a state
 * where a session must exist — they have to be able to change it — but the portal must
 * not open, since a member of bank staff knows that password.
 */
@Service
public class CustomerAuthService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthService.class);

    /**
     * The same answer for a wrong password and an unknown customer.
     *
     * <p>Anything that distinguishes them turns the sign-in form into a way to find out
     * who banks here. That includes response time, which is why a failed lookup still
     * runs a password comparison below.
     */
    private static final String GENERIC_FAILURE =
            "The details you entered do not match an account.";

    /** Codes per customer per window. Lower than registration: this targets one account. */
    private static final int MAX_CODES_PER_WINDOW = 3;

    private static final Duration RATE_WINDOW = Duration.ofMinutes(10);

    /**
     * A BCrypt hash of a value nobody holds.
     *
     * <p>Compared against when the identifier is unknown, so that an unknown customer
     * costs the same time as a wrong password. Without it, sign-in is a timing oracle for
     * which email addresses have accounts: a miss returns in microseconds, a hit takes the
     * ~100ms BCrypt is designed to take.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final CustomerRepository customers;
    private final LoginChallengeRepository challenges;
    private final TrustedDevices trustedDevices;
    private final SignInProperties signIn;
    private final LoginAttempts attempts;
    private final SignInRepository signIns;
    private final PasswordEncoder passwords;
    private final Mailer mailer;
    private final OutboundMailProperties mailSettings;

    CustomerAuthService(
            CustomerRepository customers,
            LoginChallengeRepository challenges,
            TrustedDevices trustedDevices,
            SignInProperties signIn,
            LoginAttempts attempts,
            SignInRepository signIns,
            PasswordEncoder passwords,
            Mailer mailer,
            OutboundMailProperties mailSettings) {
        this.customers = customers;
        this.challenges = challenges;
        this.attempts = attempts;
        this.signIns = signIns;
        this.passwords = passwords;
        this.mailer = mailer;
        this.mailSettings = mailSettings;
        this.trustedDevices = trustedDevices;
        this.signIn = signIn;
    }

    /** What a sign-in step produced. */
    public record LoginOutcome(
            String outcome, String challengeId, String deliveryHint, CustomerEntity customer) {

        static LoginOutcome challenge(String id, String hint) {
            return new LoginOutcome("CHALLENGE_REQUIRED", id, hint, null);
        }

        static LoginOutcome passwordChangeRequired(CustomerEntity customer) {
            return new LoginOutcome("PASSWORD_CHANGE_REQUIRED", null, null, customer);
        }

        static LoginOutcome complete(CustomerEntity customer) {
            return new LoginOutcome("COMPLETE", null, null, customer);
        }
    }

    /* ------------------------------------------------------------------ step 1 */

    /**
     * Checks the password and decides what happens next.
     *
     * <p>Three possible answers now, not two. A browser this customer has already proved
     * with an emailed code skips straight to a session; anything else gets a challenge,
     * and the session is created only by {@link #verify(String, String)}.
     *
     * @param deviceToken the value of the device cookie, or null when the browser has
     *     none. It is CHECKED AFTER THE PASSWORD, always: a token that could shorten the
     *     path before the password was verified would be a credential in its own right,
     *     and a stolen cookie would then be a sign-in rather than merely a skipped code.
     */
    @Transactional
    public LoginOutcome login(
            String identifier, String password, String deviceToken, String userAgent) {
        Optional<CustomerEntity> found = customers.findByEmailIgnoreCase(normalise(identifier));

        // Always compare, even on a miss. See DUMMY_HASH.
        String hash = found.map(CustomerEntity::passwordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwords.matches(password, hash);

        if (found.isEmpty() || !passwordMatches) {
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }

        CustomerEntity customer = found.get();

        /*
         * Status is checked AFTER the password, on purpose. Reporting "this account is
         * awaiting approval" to someone who did not supply the right password would
         * confirm the account exists.
         */
        switch (customer.status()) {
            case PENDING_APPROVAL ->
                    throw new AccountNotReadyException(
                            "Your account has been created but is still waiting for approval. We"
                                    + " will email you as soon as it is ready.");
            case REJECTED ->
                    throw new AccountNotReadyException(
                            "This application was not approved. Please contact the bank.");
            case SUSPENDED ->
                    throw new AccountNotReadyException(
                            "This account is suspended. Please contact the bank.");
            case ACTIVE -> {
                // Carry on below.
            }
        }

        /*
         * A temporary password does NOT get a one-time code.
         *
         * It is single-use and about to be discarded, and the only thing the resulting
         * session can do is replace it. Requiring a second factor to change a password
         * that bank staff already know protects nothing and adds a step at the worst
         * possible moment — a customer's first contact with the service.
         */
        /*
         * An emailed temporary password that has gone stale.
         *
         * Checked AFTER the password matched, so it reveals nothing to someone guessing:
         * a wrong password still gets the generic failure. The message says what happened
         * and what to do, because the customer has done nothing wrong — they simply
         * opened the email too late, and the alternative would be a correct password that
         * silently does not work.
         */
        if (customer.temporaryPasswordExpired(Instant.now())) {
            log.info(
                    "Customer {} tried an expired temporary password",
                    customer.customerNumber());
            throw new AccountNotReadyException(
                    "The temporary password we emailed you has expired. Please call the bank"
                            + " and we will send you a new one.");
        }

        if (customer.mustChangePassword()) {
            /*
             * Recorded, even though no code was required.
             *
             * The sign-in history is the control a customer uses to notice access that was
             * not theirs, and THIS is the sign-in most worth having in it: the temporary
             * password was read off a screen by a member of staff and handed over on paper
             * or at a counter, so it is the credential most likely to have been used by
             * somebody else first. Leaving it out meant that if it had been, the customer
             * would see "this is your first sign-in" and have no trace of the one before.
             */
            signIns.save(
                    new SignInEntity(
                            customer.id(),
                            SignInMethod.TEMPORARY_PASSWORD,
                            DeviceDescription.from(userAgent)));
            log.info(
                    "Customer {} signed in with the temporary password",
                    customer.customerNumber());
            return LoginOutcome.passwordChangeRequired(customer);
        }

        /*
         * NO SECOND FACTOR, WHEN THE BANK HAS TURNED IT OFF.
         *
         * Checked before the remembered-browser branch below, because when the code is
         * not required there is nothing for a browser to be trusted FOR — and running the
         * trust check first would mean a customer on an untrusted browser got no code and
         * a log line saying their token was refused, which is a confusing record of a
         * thing that did not matter.
         *
         * The sign-in is recorded either way. See SignInProperties for what this gives
         * up; it is not a small thing and it is written down rather than assumed.
         */
        if (!signIn.codeRequired()) {
            signIns.save(
                    new SignInEntity(
                            customer.id(),
                            SignInMethod.PASSWORD_ONLY,
                            DeviceDescription.from(userAgent)));
            log.info(
                    "Customer {} signed in with a password only; the sign-in code is turned off",
                    customer.customerNumber());
            return LoginOutcome.complete(customer);
        }

        /*
         * A BROWSER THAT HAS ALREADY DONE THIS. The password has been checked and the
         * account's status has been checked above; the emailed code is the only step left
         * and this browser has completed it within the last thirty days.
         *
         * The sign-in is recorded exactly as the code path records it. A sign-in that
         * skipped the code is still a sign-in, and leaving it out of the history would
         * hide precisely the ones a customer would want to see if a trusted machine were
         * ever used without them.
         */
        if (trustedDevices.trustsAny(customer.id(), deviceToken)) {
            signIns.save(
                    new SignInEntity(
                            customer.id(),
                            SignInMethod.TRUSTED_BROWSER,
                            DeviceDescription.from(userAgent)));
            log.info(
                    "Customer {} signed in from a browser that was already trusted",
                    customer.customerNumber());
            return LoginOutcome.complete(customer);
        }

        return LoginOutcome.challenge(issueCode(customer), maskEmail(customer.email()));
    }

    /* ------------------------------------------------------------------ step 2 */

    /** Checks the code and, on success, returns the customer a session may be built for. */
    @Transactional
    public CustomerEntity verify(String challengeId, String code, String userAgent) {
        LoginChallengeEntity challenge =
                challenges
                        .findById(parseId(challengeId))
                        .orElseThrow(
                                () ->
                                        new BusinessRuleException(
                                                "This sign-in attempt has expired. Please sign in"
                                                        + " again."));

        if (!challenge.usable(Instant.now())) {
            throw new BusinessRuleException(
                    "This sign-in attempt has expired. Please sign in again.");
        }

        if (!VerificationCodes.matches(code, challenge.codeHash())) {
            // Counted in its own transaction — this one is about to roll back.
            int left = attempts.recordFailure(challenge.id());
            throw new BusinessRuleException(
                    left <= 0
                            ? "That code is not correct, and there are no attempts left. Please"
                                    + " sign in again."
                            : "That code is not correct. Check the code we sent you and type it"
                                    + " again. "
                                    + left
                                    + (left == 1 ? " attempt left." : " attempts left."));
        }

        challenge.consume();
        challenges.save(challenge);

        CustomerEntity customer =
                customers
                        .findById(challenge.customerId())
                        .orElseThrow(() -> new UnauthenticatedException(GENERIC_FAILURE));

        if (!customer.canSignIn()) {
            throw new UnauthenticatedException(GENERIC_FAILURE);
        }

        signIns.save(
                new SignInEntity(
                        customer.id(),
                        SignInMethod.PASSWORD_AND_CODE,
                        DeviceDescription.from(userAgent)));
        log.info("Customer {} signed in", customer.customerNumber());
        return customer;
    }

    /** A fresh code for an existing attempt. */
    @Transactional
    public LoginOutcome resend(String challengeId) {
        LoginChallengeEntity previous =
                challenges
                        .findById(parseId(challengeId))
                        .orElseThrow(
                                () ->
                                        new BusinessRuleException(
                                                "This sign-in attempt has expired. Please sign in"
                                                        + " again."));

        CustomerEntity customer =
                customers
                        .findById(previous.customerId())
                        .orElseThrow(() -> new UnauthenticatedException(GENERIC_FAILURE));

        return LoginOutcome.challenge(issueCode(customer), maskEmail(customer.email()));
    }

    /* ------------------------------------------------------- password change */

    /**
     * Replaces the temporary password an administrator issued.
     *
     * <p>Asks for the temporary one again rather than trusting the session, because the
     * session was created by presenting it moments ago and an unattended browser is the
     * obvious way this gets abused.
     */
    @Transactional
    public CustomerEntity changeTemporaryPassword(
            UUID customerId, String currentPassword, String newPassword) {

        CustomerEntity customer =
                customers
                        .findById(customerId)
                        .orElseThrow(() -> new UnauthenticatedException(GENERIC_FAILURE));

        if (!passwords.matches(currentPassword, customer.passwordHash())) {
            throw new BusinessRuleException(
                    "That is not your current password. Check it and type it again.");
        }

        String problem = passwordProblem(newPassword);
        if (problem != null) {
            throw new BusinessRuleException(problem);
        }

        if (passwords.matches(newPassword, customer.passwordHash())) {
            throw new BusinessRuleException(
                    "Choose a password that is different from the temporary one.");
        }

        customer.replacePassword(passwords.encode(newPassword));
        customers.save(customer);

        log.info("Customer {} replaced their temporary password", customer.customerNumber());
        return customer;
    }

    /**
     * Everything wrong with a password, in one message.
     *
     * <p>Returning the first fault only makes someone guess the rules one submit at a
     * time — too short, then no capital, then no number. People give up around the third.
     */
    static String passwordProblem(String value) {
        if (value == null) return "Enter a new password.";

        List<String> missing = new java.util.ArrayList<>();
        if (value.length() < 12) missing.add("at least 12 characters");
        if (!value.matches(".*[a-z].*")) missing.add("a small letter");
        if (!value.matches(".*[A-Z].*")) missing.add("a capital letter");
        if (!value.matches(".*\\d.*")) missing.add("a number");

        if (missing.isEmpty()) return null;

        String last = missing.get(missing.size() - 1);
        String list =
                missing.size() == 1
                        ? last
                        : String.join(", ", missing.subList(0, missing.size() - 1)) + " and " + last;
        return "Your password still needs " + list + ".";
    }

    /* ------------------------------------------------------------- reading */

    @Transactional(readOnly = true)
    public Optional<CustomerEntity> find(UUID id) {
        return customers.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<SignInEntity> previousSignIn(UUID customerId) {
        List<SignInEntity> recent =
                signIns.findByCustomerIdOrderBySignedInAtDesc(customerId, Limit.of(2));
        // The one before the current session, which is what "last signed in" means.
        return recent.size() > 1 ? Optional.of(recent.get(1)) : Optional.empty();
    }

    /* ------------------------------------------------------------ plumbing */

    private String issueCode(CustomerEntity customer) {
        long recent =
                challenges.countByCustomerIdAndCreatedAtAfter(
                        customer.id(), Instant.now().minus(RATE_WINDOW));

        if (recent >= MAX_CODES_PER_WINDOW) {
            throw new RateLimitedException(
                    "You have asked for a code too many times. Please wait a few minutes and try"
                            + " again.",
                    RATE_WINDOW);
        }

        String code = VerificationCodes.generate();
        LoginChallengeEntity challenge =
                LoginChallengeEntity.issue(
                        customer.id(), VerificationCodes.hash(code), mailSettings.codeValidity());
        challenges.save(challenge);

        mailer.send(
                OutboxKind.EMAIL_VERIFICATION,
                customer.email(),
                "Your Zigama CSS sign-in code",
                String.join(
                        "\n",
                        "Your sign-in code is " + code + ".",
                        "",
                        "It expires in " + mailSettings.codeValidity().toMinutes() + " minutes.",
                        "",
                        "If you are not trying to sign in, somebody may have your password.",
                        "Change it as soon as you can and call the number on the back of your card.",
                        "",
                        "Zigama CSS will never ask you for this code by phone, SMS or email."));

        return challenge.id().toString();
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at < 1) return "***";
        return email.charAt(0) + "*".repeat(Math.max(1, at - 1)) + email.substring(at);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    "This sign-in attempt has expired. Please sign in again.");
        }
    }

    /** 401, phrased so it cannot distinguish a wrong password from an unknown customer. */
    public static class UnauthenticatedException extends ApiException {
        private static final long serialVersionUID = 1L;

        public UnauthenticatedException(String safeMessage) {
            super(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    rw.bank.ibanking.common.api.ApiErrorCode.UNAUTHENTICATED,
                    safeMessage);
        }
    }

    /**
     * 423 Locked: the credentials were right, the account is not usable yet.
     *
     * <p>A distinct status because the client must NOT treat it as a failed sign-in — the
     * customer needs to be told to wait for approval, not to check their password.
     */
    public static class AccountNotReadyException extends ApiException {
        private static final long serialVersionUID = 1L;

        public AccountNotReadyException(String safeMessage) {
            super(
                    org.springframework.http.HttpStatus.LOCKED,
                    rw.bank.ibanking.common.api.ApiErrorCode.BUSINESS_RULE_VIOLATION,
                    safeMessage);
        }
    }
}
