package rw.bank.ibanking.onboarding.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.SignInEntity;
import rw.bank.ibanking.onboarding.domain.TrustedDeviceEntity;
import rw.bank.ibanking.onboarding.service.SecurityService;
import rw.bank.ibanking.onboarding.service.TrustedDevices;

/**
 * THE CUSTOMER'S OWN SECURITY PAGE.
 *
 * <p>WHAT THIS REPLACED: nothing, which was the problem. The portal shipped a complete
 * security screen calling {@code /security/devices}, {@code /security/events} and {@code
 * /security/password}, and not one of the three existed here. MSW answered all of them
 * from arrays that start empty on every page load, so the sign-in history showed nothing
 * whatever had happened to the account and the revoke button ended trust in a browser that
 * had never been trusted.
 *
 * <p>EVERY ENDPOINT IS SCOPED TO THE CALLER, taken from the session and never from the
 * request. There is no customer id in any path or body here on purpose: a security page
 * that accepted one would be a way to read somebody else's sign-in history by editing a
 * URL, and this is the screen on which that matters most.
 *
 * <p>NOTHING HERE RETURNS OR ACCEPTS A TOKEN. Device rows are identified by their own id;
 * the token exists only in the customer's cookie, hashed in the table, and is never in a
 * response — see V14. The change-password body is read once and nothing retains it.
 */
@RestController
@RequestMapping("/api/v1/security")
class SecurityController {

    private final SecurityService security;

    SecurityController(SecurityService security) {
        this.security = security;
    }

    /* --------------------------------------------------------------- shapes */

    /**
     * A browser the customer has trusted.
     *
     * <p>NO LOCATION FIELD, and the frontend's old type had one. There is no geo-IP lookup
     * in this service and the table has never held a location, so the only way to populate
     * it was to make one up — which is exactly what {@code sign_ins} did with "Kigali,
     * Rwanda" for four call sites and a year. {@code device} is what the User-Agent said
     * when trust was granted, and it is absent when the client said nothing.
     *
     * <p>{@code current} IS COMPUTED FROM THE COOKIE, not asserted by the client, because
     * it is what stops somebody revoking the browser they are sitting at.
     */
    record DeviceView(
            String id,
            String device,
            String trustedAt,
            String lastUsedAt,
            String expiresAt,
            boolean current,
            boolean revoked,
            String revokedReason) {}

    /**
     * One sign-in.
     *
     * <p>NO {@code outcome} FIELD. The frontend's type had SUCCESS or FAILURE and nothing
     * ever wrote FAILURE — {@code sign_ins} records sign-ins that happened. A column that
     * is always SUCCESS is a column that teaches a customer their history is complete when
     * it is not. Failed attempts are worth recording and need their own table and retention
     * decision; docs/OPEN-ITEMS.md says so rather than this shape implying otherwise.
     */
    record SignInView(String at, String method, String device) {}

    record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password.") String currentPassword,
            @NotBlank(message = "Enter a new password.") String newPassword) {}

    /**
     * What the customer is told after a password change.
     *
     * <p>CARRIES THE COUNT, because changing a password signs every remembered browser out
     * and the customer did not ask for that. Without it they meet an unexplained code
     * request on their next sign-in from a machine that had not needed one for a month.
     */
    record PasswordChanged(int browsersSignedOut) {}

    /* -------------------------------------------------------------- devices */

    @GetMapping("/devices")
    List<DeviceView> devices(HttpServletRequest request) {
        UUID customerId = AuthController.currentCustomerId();
        String cookie = deviceCookie(request);

        return security.devices(customerId).stream()
                .map(device -> view(device, security.isCurrentDevice(device.id(), cookie)))
                .toList();
    }

    private static DeviceView view(TrustedDeviceEntity device, boolean current) {
        return new DeviceView(
                device.id().toString(),
                device.device(),
                device.createdAt().toString(),
                device.lastUsedAt() == null ? null : device.lastUsedAt().toString(),
                device.expiresAt().toString(),
                current,
                device.isRevoked(),
                device.revokedReason());
    }

    /**
     * Ends trust in one browser.
     *
     * <p>DELETE, and it withdraws trust rather than deleting the row: V14 keeps revoked
     * devices because "this browser was trusted and then it was not" is the history
     * somebody investigating an unauthorised sign-in needs. The verb describes what the
     * customer is doing to their own access, not what happens to the table.
     */
    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeDevice(@PathVariable UUID id, HttpServletRequest request) {
        security.revokeDevice(AuthController.currentCustomerId(), id, deviceCookie(request));
    }

    /* -------------------------------------------------------------- history */

    @GetMapping("/events")
    List<SignInView> events() {
        return security.history(AuthController.currentCustomerId()).stream()
                .map(SecurityController::view)
                .toList();
    }

    private static SignInView view(SignInEntity signIn) {
        return new SignInView(
                signIn.signedInAt().toString(), signIn.method().label(), signIn.device());
    }

    /* ------------------------------------------------------------- password */

    @PostMapping("/password")
    PasswordChanged changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        int revoked =
                security.changePassword(
                        AuthController.currentCustomerId(),
                        request.currentPassword(),
                        request.newPassword());

        return new PasswordChanged(revoked);
    }

    /* ------------------------------------------------------------- plumbing */

    /**
     * The device cookie, from the request.
     *
     * <p>READ FROM THE COOKIE JAR, never from a header or body the page could set. The one
     * thing this value decides is which row counts as "the browser you are using now", and
     * a client-supplied version of that would let somebody mark any device current — or
     * mark the one they wanted to revoke as something else.
     */
    private static String deviceCookie(HttpServletRequest request) {
        Cookie[] jar = request.getCookies();
        if (jar == null) return null;

        for (Cookie candidate : jar) {
            if (TrustedDevices.COOKIE.equals(candidate.getName())) return candidate.getValue();
        }
        return null;
    }
}
