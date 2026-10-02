package rw.bank.ibanking.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import rw.bank.ibanking.onboarding.domain.CustomerEntity;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;

/**
 * Checks, on every request, that the signed-in customer is still allowed in.
 *
 * <p>Without this, freezing an account does almost nothing. {@code canSignIn()} was only
 * consulted when a customer signed IN; a session created a minute earlier carried
 * {@code ROLE_CUSTOMER} and kept working regardless of what the account's status became.
 * A manager freezing an account because they believe it is compromised would have stopped
 * the next sign-in and not the person already inside it — which is the opposite of the
 * only case where the timing matters.
 *
 * <p>So the status is re-read per request rather than trusted from the session. That is a
 * database query on every authenticated call, and it is the right trade: the alternative
 * is a window, between a freeze and whenever the session happens to end, in which the
 * bank believes an account is frozen and it is not. If that query ever becomes a
 * bottleneck the answer is a short-lived cache with explicit invalidation on freeze, not
 * removing the check.
 *
 * <p>It deliberately covers the {@code MUST_CHANGE_PASSWORD} state too. An account frozen
 * between approval and the customer's first sign-in must not be completable.
 *
 * <p>NOT A {@code @Component}, AND THAT IS THE POINT.
 *
 * <p>It was one, and the mistake was invisible because everything passed. Spring Boot
 * auto-registers any {@code Filter} bean into the servlet chain as well, so this filter ran
 * twice: once where {@link SecurityConfig} places it, and once because it existed. Taking it
 * out of the security chain entirely changed nothing — all nine freeze tests still passed,
 * because the accidental registration was doing the work. A control that cannot be removed
 * without the suite noticing is a control nobody is actually testing, and the careful
 * positioning after {@code AuthorizationFilter} was decorative while the real copy ran
 * wherever auto-configuration happened to put it.
 *
 * <p>So it is constructed with {@code new} in {@link SecurityConfig} and never exposed as a
 * bean. One registration, in a known position, and deleting that line now turns the suite
 * red. If this ever needs to be a bean again, register it with a
 * {@code FilterRegistrationBean} whose {@code setEnabled(false)} suppresses the servlet
 * copy — and check that removing it from the chain still fails a test.
 */
public class CustomerStillAllowedFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CustomerStillAllowedFilter.class);

    private final CustomerRepository customers;

    public CustomerStillAllowedFilter(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!isCustomerSession(authentication)) {
            chain.doFilter(request, response);
            return;
        }

        UUID customerId;
        try {
            customerId = UUID.fromString((String) authentication.getPrincipal());
        } catch (IllegalArgumentException e) {
            // Not a customer session after all. Leave it to the rest of the chain.
            chain.doFilter(request, response);
            return;
        }

        CustomerEntity customer = customers.findById(customerId).orElse(null);

        if (customer == null || !customer.canSignIn()) {
            /*
             * Ended here and now, not merely refused. Leaving the session valid would
             * mean a frozen customer keeps a usable cookie that starts working again the
             * moment the account is restored — or, worse, that some endpoint which
             * forgets to check lets it through.
             */
            log.info(
                    "Ending the session of customer {}: {}",
                    customerId,
                    customer == null ? "no longer exists" : "status is " + customer.status());

            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            /*
             * Says nothing about WHY. A freeze may concern an investigation the customer
             * must not be tipped off about, and in every other case the branch is the
             * right place to hear it — not a JSON body.
             */
            response.getWriter()
                    .write(
                            """
                            {"status":401,"code":"UNAUTHENTICATED",\
                            "message":"You have been signed out. Please contact the bank."}\
                            """);
            return;
        }

        chain.doFilter(request, response);
    }

    /** A customer session: the principal is the customer's id as a String. */
    private static boolean isCustomerSession(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof String principal
                && !"anonymousUser".equals(principal);
    }
}
