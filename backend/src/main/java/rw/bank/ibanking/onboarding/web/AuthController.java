package rw.bank.ibanking.onboarding.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.service.CustomerAuthService;
import rw.bank.ibanking.onboarding.service.PasswordResetService;
import rw.bank.ibanking.onboarding.service.TrustedDevices;

/**
 * Customer sign-in.
 *
 * <p>The session is created here and nowhere else, and only after the step that earns it.
 * {@code /auth/login} never produces one: it returns a challenge. The exception is a
 * temporary password, which produces a session that can do exactly one thing — replace
 * itself — enforced by the {@code MUST_CHANGE_PASSWORD} authority below.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    /**
     * The authority a customer holds while still on the temporary password.
     *
     * <p>They get THIS INSTEAD OF the customer authority, not as well as it. That is the
     * whole control: every protected endpoint requires {@code ROLE_CUSTOMER}, so a session
     * in this state is refused everywhere except the password-change endpoint. Adding it
     * alongside would leave the portal wide open to a password bank staff know.
     */
    static final String MUST_CHANGE_PASSWORD = "MUST_CHANGE_PASSWORD";

    static final String CUSTOMER = "ROLE_CUSTOMER";

    private final CustomerAuthService auth;
    private final TrustedDevices trustedDevices;
    private final PasswordResetService passwordResets;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    AuthController(
            CustomerAuthService auth,
            TrustedDevices trustedDevices,
            PasswordResetService passwordResets) {
        this.auth = auth;
        this.trustedDevices = trustedDevices;
        this.passwordResets = passwordResets;
    }

    /**
     * The cookie that remembers this browser.
     *
     * <p>Every flag here is doing something:
     *
     * <ul>
     *   <li>HTTP-ONLY, so script on the page cannot read it. This token stands in for a
     *       code emailed to the customer, and a cross-site-scripting bug that could read
     *       it would be a bug that defeats the second factor for thirty days.
     *   <li>SECURE, outside development. Sent over plain HTTP it would be readable by
     *       anybody on the network, which for a thirty-day credential is worse than not
     *       having one. Not set when the request itself arrived over HTTP, because a
     *       Secure cookie on {@code http://localhost} is simply dropped and the developer
     *       is left wondering why trust never sticks.
     *   <li>SAME-SITE=Lax, so another site cannot make the browser send it. Lax rather
     *       than Strict because sign-in is a top-level POST from our own page, which Lax
     *       allows and Strict would not break either — Lax is the weaker of the two that
     *       still closes the cross-site case, and is chosen for predictability.
     *   <li>PATH, narrowed to the auth endpoints. The token is only ever read while
     *       signing in, so there is no reason to attach it to every request for an
     *       account balance.
     * </ul>
     */
    private static Cookie deviceCookie(String token, HttpServletRequest request) {
        Cookie cookie = new Cookie(TrustedDevices.COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge((int) TrustedDevices.TRUST_FOR.toSeconds());
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    /* -------------------------------------------------------------- shapes */

    record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}

    record VerifyRequest(@NotBlank String challengeId, @NotBlank String code) {}

    record ResendRequest(@NotBlank String challengeId) {}

    record ChangeTemporaryPasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {}

    record Challenge(
            String challengeId, int otpLength, long resendAfterSeconds, String deliveryHint) {}

    /** Matches the frontend's LoginResult: an outcome, and a challenge when there is one. */
    record LoginResult(String outcome, Challenge challenge) {}

    /* --------------------------------------------------------------- login */

    @PostMapping("/login")
    LoginResult login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        /*
         * The device cookie, read from the request rather than taken from the body. A
         * client-supplied field would let anybody claim a token; a cookie is set by this
         * server, HttpOnly, and cannot be read or written by script on the page.
         */
        var outcome =
                auth.login(
                        request.identifier(),
                        request.password(),
                        cookie(httpRequest, TrustedDevices.COOKIE),
                        /*
                         * The User-Agent, so the recorded sign-in says what it was done
                         * on. Read from the header rather than the body for the obvious
                         * reason, and it is never trusted for anything: it labels a row
                         * the customer reads and decides nothing.
                         */
                        httpRequest.getHeader("User-Agent"));

        if ("PASSWORD_CHANGE_REQUIRED".equals(outcome.outcome())) {
            // A session that can only change the password. See MUST_CHANGE_PASSWORD.
            establishSession(outcome.customer(), true, httpRequest, httpResponse);
            return new LoginResult(outcome.outcome(), null);
        }

        /*
         * COMPLETE: this browser had already proved itself with an emailed code, so the
         * password was the only step left and there is a session now. Deliberately the
         * same outcome string the verify step returns, so the client has one "you are in"
         * path rather than two that can drift apart.
         */
        if ("COMPLETE".equals(outcome.outcome())) {
            establishSession(outcome.customer(), false, httpRequest, httpResponse);
            return new LoginResult(outcome.outcome(), null);
        }

        // CHALLENGE_REQUIRED: no session yet, deliberately.
        return new LoginResult(
                outcome.outcome(),
                new Challenge(outcome.challengeId(), 6, 30, outcome.deliveryHint()));
    }

    /** One named cookie from the request, or null. */
    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] jar = request.getCookies();
        if (jar == null) return null;

        for (Cookie candidate : jar) {
            if (name.equals(candidate.getName())) return candidate.getValue();
        }
        return null;
    }

    @PostMapping("/verify")
    LoginResult verify(
            @Valid @RequestBody VerifyRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        CustomerEntity customer =
                auth.verify(
                        request.challengeId(),
                        request.code(),
                        httpRequest.getHeader("User-Agent"));
        establishSession(customer, false, httpRequest, httpResponse);

        /*
         * THE BROWSER HAS NOW PROVED ITSELF, so remember it and stop asking. This is the
         * only place a device token is ever minted: completing the emailed-code step is
         * the thing being remembered, and minting one anywhere else would be trusting a
         * browser that had not done it.
         */
        httpResponse.addCookie(
                deviceCookie(
                        trustedDevices.rememberIn(
                                customer.id(),
                                cookie(httpRequest, TrustedDevices.COOKIE),
                                /* Labels the row on the customer's own security page, so
                                   they can tell their browsers apart well enough to
                                   revoke one. See TrustedDeviceEntity.device. */
                                httpRequest.getHeader("User-Agent")),
                        httpRequest));

        return new LoginResult("COMPLETE", null);
    }

    @PostMapping("/resend")
    Challenge resend(@Valid @RequestBody ResendRequest request) {
        var outcome = auth.resend(request.challengeId());
        return new Challenge(outcome.challengeId(), 6, 30, outcome.deliveryHint());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            /*
             * Invalidated server-side, not just cleared client-side. A session the server
             * still honours is not ended, whatever the browser has been told, and "log out"
             * on a shared machine has to mean it.
             */
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    /* ----------------------------------------------------- password change */

    @PostMapping("/password/change-temporary")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeTemporaryPassword(
            @Valid @RequestBody ChangeTemporaryPasswordRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        UUID customerId = currentCustomerId();
        CustomerEntity customer =
                auth.changeTemporaryPassword(
                        customerId, request.currentPassword(), request.newPassword());

        /*
         * The session is re-established with the full customer authority, so the portal
         * opens without a second sign-in. A new session id, because the privileges
         * attached to this session just changed — reusing it would mean an id that was
         * valid under the old, lesser state is valid under the greater one.
         */
        HttpSession old = httpRequest.getSession(false);
        if (old != null) old.invalidate();
        establishSession(customer, false, httpRequest, httpResponse);
    }

    /* --------------------------------------------------- a forgotten password */

    record ForgotPasswordRequest(@NotBlank String identifier) {}

    /**
     * "I have forgotten my password."
     *
     * <p>202 AND AN EMPTY BODY, WHATEVER HAPPENS. The response is identical for an address
     * that banks here and one that does not, because anything else turns this endpoint
     * into a way to find out who banks here — type an address, read the answer, repeat. The
     * service returns nothing at all, so there is nothing for this method to accidentally
     * reveal, and the screen composes the message the customer reads.
     *
     * <p>Accepted rather than No Content, because that is what actually happened: the
     * request has been accepted for a human to act on. Nothing has been reset, and no
     * credential has been created or invalidated by this call.
     *
     * <p>PUBLIC, and listed as such in SecurityConfig. A customer who cannot sign in is by
     * definition without a session.
     */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResets.request(request.identifier());
    }

    /* ------------------------------------------------------------ plumbing */

    static UUID currentCustomerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof String id)) {
            throw new CustomerAuthService.UnauthenticatedException(
                    "You have been signed out. Please sign in again.");
        }
        return UUID.fromString(id);
    }

    /**
     * Puts the customer in the session.
     *
     * <p>The principal is the customer's id, not the entity: a detached JPA entity in a
     * session is a stale copy of a row that other requests are changing, and it would be
     * serialised into a session store the moment this runs on more than one instance.
     */
    private void establishSession(
            CustomerEntity customer,
            boolean mustChangePassword,
            HttpServletRequest request,
            HttpServletResponse response) {

        var authority =
                new SimpleGrantedAuthority(mustChangePassword ? MUST_CHANGE_PASSWORD : CUSTOMER);

        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        customer.id().toString(), null, List.of(authority));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
