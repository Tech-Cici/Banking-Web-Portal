package rw.bank.ibanking.onboarding.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.repo.StaffRepository;
import rw.bank.ibanking.onboarding.service.CustomerAuthService;

/**
 * Staff sign-in, at its own endpoint with its own response shape.
 *
 * <p>Deliberately not reusing the customer's. Staff and customers are different
 * populations, and one shared "logged in" response is how a support tool ends up reachable
 * with a customer's session.
 *
 * <p>NO SECOND FACTOR, and that is a gap rather than a decision. Staff credentials open
 * other people's accounts, so they warrant more verification than a customer's — who
 * currently gets an emailed code while staff get none. Tracked in
 * frontend/docs/OPEN-ITEMS.md; it must close before this goes live.
 */
@RestController
@RequestMapping("/api/v1/auth/staff")
class StaffAuthController {

    private final StaffRepository staff;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    /** See CustomerAuthService: an unknown user must cost the same time as a wrong password. */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    StaffAuthController(StaffRepository staff, PasswordEncoder passwords) {
        this.staff = staff;
        this.passwords = passwords;
    }

    record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}

    record StaffUser(String id, String fullName, String email, String role, String branch) {
        static StaffUser of(StaffEntity s) {
            return new StaffUser(
                    s.id().toString(), s.fullName(), s.email(), s.role().name(), s.branch());
        }
    }

    record StaffLoginResult(String outcome, StaffUser staff) {}

    @PostMapping("/login")
    StaffLoginResult login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        var found = staff.findByEmailIgnoreCase(request.identifier().trim());
        String hash = found.map(StaffEntity::passwordHash).orElse(DUMMY_HASH);
        boolean matches = passwords.matches(request.password(), hash);

        if (found.isEmpty() || !matches) {
            // Same answer either way: this form must not reveal who works here.
            throw new CustomerAuthService.UnauthenticatedException(
                    "The details you entered do not match a staff account.");
        }

        StaffEntity member = found.get();

        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        member.email(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + member.role().name())));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);

        return new StaffLoginResult("COMPLETE", StaffUser.of(member));
    }

    @GetMapping("/session")
    StaffUser session() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof String email)) {
            throw new CustomerAuthService.UnauthenticatedException(
                    "You have been signed out. Please sign in again.");
        }

        return staff.findByEmailIgnoreCase(email)
                .map(StaffUser::of)
                .orElseThrow(
                        () ->
                                new CustomerAuthService.UnauthenticatedException(
                                        "You have been signed out. Please sign in again."));
    }
}
