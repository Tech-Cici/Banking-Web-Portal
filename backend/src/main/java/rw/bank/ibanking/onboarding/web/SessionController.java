package rw.bank.ibanking.onboarding.web;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.domain.SignInEntity;
import rw.bank.ibanking.onboarding.service.CustomerAuthService;
import rw.bank.ibanking.onboarding.service.CustomerEntitlements;

/**
 * Who is signed in.
 *
 * <p>The frontend calls this on every page load to decide whether to render the portal, so
 * it is the single source of truth for session state — including
 * {@code mustChangePassword}.
 *
 * <p>That field travels here, not only on the sign-in response, because of a real bug: a
 * customer still on their temporary password could reach the dashboard by typing the URL.
 * The sign-in redirect was doing the work of a control. Now the server refuses the session
 * for everything else, and this tells the client why.
 */
@RestController
@RequestMapping("/api/v1/session")
class SessionController {

    private final CustomerAuthService auth;
    private final CustomerEntitlements entitlements;

    SessionController(CustomerAuthService auth, CustomerEntitlements entitlements) {
        this.auth = auth;
        this.entitlements = entitlements;
    }

    /** Matches the frontend's SessionUser. */
    record SessionUser(
            String id,
            String fullName,
            String preferredName,
            String email,
            String phone,
            String customerNumber,
            String userType,
            List<String> permissions,
            List<CorporateView> corporates,
            String lastLoginAt,
            /**
             * What the previous sign-in was done on, when the client said.
             *
             * <p>REPLACES {@code lastLoginLocation}, which was the literal "Kigali,
             * Rwanda" on every session this service has ever issued — see
             * {@link rw.bank.ibanking.onboarding.domain.SignInMethod}. The dashboard
             * printed it as "last sign-in from Kigali, Rwanda" to every customer in the
             * world, which is a false negative in the one check the customer makes
             * themselves.
             *
             * <p>Null when the previous sign-in recorded no recognisable User-Agent, and
             * the header then says only when. A blank prompts a question; a wrong city
             * answers one that was never asked.
             */
            String lastLoginDevice,
            /** How the previous sign-in was done, in the customer's words. Null if none. */
            String lastLoginMethod) {}

    /**
     * A company the signed-in customer may act for. Matches the portal's
     * CorporateMembership.
     *
     * <p>The role travels so the portal can label it. It is not the authority —
     * {@code permissions} is what the portal branches on, and the server checks the caller
     * itself on every request regardless of either.
     */
    record CorporateView(String id, String name, String code, String role) {

        static CorporateView of(CustomerEntitlements.Company company) {
            return new CorporateView(
                    company.id().toString(),
                    company.name(),
                    company.code(),
                    company.role().name());
        }
    }

    record SessionResponse(
            SessionUser user, String activeCorporateId, boolean mustChangePassword) {}

    @GetMapping
    SessionResponse current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean mustChange =
                authentication != null
                        && authentication.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .anyMatch(AuthController.MUST_CHANGE_PASSWORD::equals);

        CustomerEntity customer =
                auth.find(AuthController.currentCustomerId())
                        .orElseThrow(
                                () ->
                                        new CustomerAuthService.UnauthenticatedException(
                                                "You have been signed out. Please sign in again."));

        var previous = auth.previousSignIn(customer.id());
        var held = entitlements.of(customer);

        return new SessionResponse(
                new SessionUser(
                        customer.id().toString(),
                        customer.fullName(),
                        firstName(customer.fullName()),
                        customer.email(),
                        customer.phone(),
                        /*
                         * Masked. The full customer number is an identifier the bank uses
                         * to find an account, so it does not belong in a payload the
                         * browser holds and every screen renders.
                         */
                        maskCustomerNumber(customer.customerNumber()),
                        held.userType().name(),
                        /*
                         * No permissions at all while the temporary password stands. The
                         * portal is closed until it is replaced, and an empty list means
                         * every permission check fails rather than relying on each screen
                         * to notice the flag.
                         *
                         * Applied to the corporate case too: a company's money is exactly
                         * the money that must not be reachable on a credential that was
                         * emailed and has not yet been changed.
                         */
                        mustChange ? List.of() : held.permissions(),
                        /*
                         * The company list stands even while the password must change.
                         * There is nothing sensitive in it that the customer does not
                         * already know — they are the contact on the application — and
                         * hiding it would leave the change-password screen unable to say
                         * who it is for.
                         */
                        held.companies().stream().map(CorporateView::of).toList(),
                        previous.map(p -> p.signedInAt().toString()).orElse(null),
                        /*
                         * BOTH MAY BE ABSENT AND THE HEADER COPES. `device` is null
                         * whenever the client sent nothing recognisable, and the method is
                         * UNKNOWN on rows V19 backfilled — neither gets a stand-in here,
                         * because supplying one in this exact spot is how the fabricated
                         * location reached the screen in the first place.
                         */
                        previous.map(SignInEntity::device).orElse(null),
                        previous.map(p -> p.method().label()).orElse(null)),
                held.activeCompanyId() == null ? null : held.activeCompanyId().toString(),
                mustChange);
    }

    private static String firstName(String fullName) {
        int space = fullName.indexOf(' ');
        return space > 0 ? fullName.substring(0, space) : fullName;
    }

    private static String maskCustomerNumber(String number) {
        return number.length() <= 4 ? "****" : "****" + number.substring(number.length() - 4);
    }
}
