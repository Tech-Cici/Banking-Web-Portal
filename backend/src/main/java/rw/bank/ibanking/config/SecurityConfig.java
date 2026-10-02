package rw.bank.ibanking.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;

/**
 * HTTP security for the API.
 *
 * <p>PHASE 1 POSTURE — deny by default. Only the health endpoint is public; every other path
 * returns 401 because no authentication mechanism exists yet. That is deliberate: an open
 * API is a worse starting point than a closed one, and each endpoint opens as its phase
 * lands.
 *
 * <p>PHASE 3 will replace the placeholder below with an OAuth2 resource server
 * ({@code .oauth2ResourceServer(o -> o.jwt(...))}) per the blueprint's OIDC architecture.
 * Nothing else in this class needs to change for that.
 *
 * <p>CSRF IS ENABLED, and that comment's condition is why.
 *
 * <p>It used to be disabled, on the stated assumption that sessions would be bearer-token
 * — where the browser does not attach the credential automatically and CSRF does not
 * apply. The frontend turned out to send {@code credentials: 'include'} and expects a
 * cookie session, which is exactly the case the old comment said MUST re-enable it: with a
 * cookie, any page on the internet can make the browser issue an authenticated request to
 * this API, and without CSRF protection that request goes through.
 *
 * <p>{@code CookieCsrfTokenRepository.withHttpOnlyFalse()} because the SPA has to read the
 * token to echo it back in a header. That is safe in a way it would not be for the session
 * cookie itself: the CSRF token is not a credential, and an attacker who can read it
 * already has script execution on the page, at which point CSRF is the least of it.
 *
 * <p>Sessions are {@code IF_REQUIRED} rather than {@code STATELESS}. One consequence worth
 * knowing before this runs on more than one instance: the session lives in Tomcat's memory,
 * so a second replica would not recognise a session created by the first. Spring Session
 * with Redis or JDBC is the answer then, and it is a configuration change rather than a
 * rewrite. Flagged in frontend/docs/OPEN-ITEMS.md.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Endpoints reachable without authentication.
     *
     * <p>Kept deliberately short. Add a path only when there is a reason it cannot require a
     * session — never to unblock local development.
     */
    private static final String[] PUBLIC_ENDPOINTS = {
        "/api/v1/health",
        "/actuator/health",
        "/actuator/health/**",

        /*
         * Registration. Public because it is what somebody uses BEFORE they have a login —
         * there is no session to require.
         *
         * These are the only unauthenticated write endpoints in the service, and they send
         * email to an address the caller supplies. That combination is an open relay unless
         * it is defended, so RegistrationService rate-limits per address, counts failed code
         * attempts, and expires codes in minutes. Do not add a path to this list without
         * the equivalent.
         */
        "/api/v1/registration/personal/start",
        "/api/v1/registration/personal/verify",
        "/api/v1/registration/personal/complete",
        "/api/v1/registration/otp/resend",

        /*
         * Company registration. Added with the rate limit the note above requires:
         * RegistrationService.startBusiness calls the same enforceRateLimit as the
         * personal flow, shares the same verification table, and its codes expire and
         * count failed attempts through the same code. It is not a second implementation.
         *
         * `/business/verify` is absent on purpose — both flows verify through
         * `/personal/verify`, which works off the stored challenge rather than the path.
         * Listing a path that does not exist is how this list stops describing reality.
         */
        "/api/v1/registration/business/start",
        "/api/v1/registration/business/complete",

        // Staff sign-in. Public for the same reason: it is how a session is obtained.
        "/api/v1/auth/staff/login",

        /*
         * Customer sign-in. Public because it is how a session is obtained — but note
         * that "public" here means "no session required to call it", not "unprotected":
         * /auth/login checks a password, /auth/verify checks a one-time code, and both
         * are rate limited by the same mechanism as registration.
         */
        "/api/v1/auth/login",
        "/api/v1/auth/verify",
        "/api/v1/auth/resend",
        "/api/v1/auth/logout",

        /*
         * "I have forgotten my password". Public for the same reason as sign-in, and more
         * bluntly: a customer who cannot sign in has no session by definition, so a
         * session-protected reset request would only ever work for people who did not need
         * it. It creates no credential and answers identically for an address that banks
         * here and one that does not — see AuthController.forgotPassword.
         */
        "/api/v1/auth/password/forgot"
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            /*
             * The repository, not the filter. CustomerStillAllowedFilter is constructed
             * here rather than injected because a Filter *bean* is auto-registered into
             * the servlet chain as well as this one — see that class for how that hid a
             * broken configuration behind a green suite.
             */
            CustomerRepository customers,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource)
            throws Exception {

        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(
                                                CookieCsrfTokenRepository.withHttpOnlyFalse())
                                        /*
                                         * Sign-in and registration are exempt. A CSRF token
                                         * protects an existing session from being used
                                         * without the user's intent; before sign-in there
                                         * is no session to protect, and requiring a token
                                         * to obtain one is a chicken-and-egg problem that
                                         * ends in the token being fetched by an
                                         * unauthenticated GET anyway.
                                         */
                                        .ignoringRequestMatchers(
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/verify",
                                                "/api/v1/auth/resend",
                                                "/api/v1/auth/staff/login",
                                                "/api/v1/auth/password/forgot",
                                                "/api/v1/registration/**")
                                        /*
                                         * So the portal can actually complete the
                                         * handshake. See SpaCsrf: the default masks the
                                         * token per request but writes the raw value to
                                         * the cookie, so a client that echoes the cookie
                                         * in a header is refused. Without this every
                                         * authenticated POST returns 403.
                                         */
                                        .csrfTokenRequestHandler(new SpaCsrf.Handler()))
                /*
                 * And so the cookie is written at all. The token is loaded lazily, and on
                 * a JSON API nothing reads it during a request, so without this filter the
                 * browser never receives a token to echo.
                 */
                .addFilterAfter(new SpaCsrf.Cookie(), CsrfFilter.class)
                /*
                 * After authorisation, so it only costs a query on requests that got
                 * that far, and before any controller can act on a session whose
                 * customer has since been frozen. See CustomerStillAllowedFilter.
                 */
                .addFilterAfter(
                        new CustomerStillAllowedFilter(customers), AuthorizationFilter.class)
                // No server-rendered auth surfaces on an API.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        /*
                                         * A new session id on sign-in, so a fixed id planted
                                         * beforehand cannot be adopted once the victim
                                         * authenticates.
                                         */
                                        .sessionFixation(fixation -> fixation.newSession()))
                .exceptionHandling(
                        handling ->
                                handling
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .headers(
                        headers ->
                                headers
                                        .frameOptions(frame -> frame.deny())
                                        .contentTypeOptions(Customizer.withDefaults())
                                        .referrerPolicy(Customizer.withDefaults())
                                        // HSTS is emitted by the gateway/ingress in front of this
                                        // service, which is the only hop that terminates TLS.
                                        .httpStrictTransportSecurity(hsts -> hsts.disable()))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_ENDPOINTS)
                                        .permitAll()
                                        /*
                                         * The one thing a session on a temporary password
                                         * may do. Its authority is MUST_CHANGE_PASSWORD
                                         * INSTEAD OF ROLE_CUSTOMER, so the rule below
                                         * closes everything else to it automatically —
                                         * rather than each endpoint having to remember to
                                         * check a flag, which is how the dashboard became
                                         * reachable by typing its URL.
                                         */
                                        .requestMatchers(
                                                "/api/v1/auth/password/change-temporary")
                                        .hasAnyAuthority("MUST_CHANGE_PASSWORD", "ROLE_CUSTOMER")
                                        /*
                                         * /session is readable in both states: the client
                                         * has to be able to ask "who am I, and must I
                                         * change my password?" before it knows where to
                                         * send the user.
                                         */
                                        .requestMatchers("/api/v1/session")
                                        .hasAnyAuthority("MUST_CHANGE_PASSWORD", "ROLE_CUSTOMER")
                                        /*
                                         * hasRole("CUSTOMER"), not authenticated().
                                         *
                                         * This is the rule the MUST_CHANGE_PASSWORD design
                                         * depends on, and authenticated() did not implement
                                         * it. A session holding MUST_CHANGE_PASSWORD *is*
                                         * authenticated, so authenticated() let it through
                                         * to everything this catch-all covers — which is
                                         * every banking endpoint that has not been written
                                         * yet. Nothing was exposed today, because the only
                                         * paths on this chain are listed above; the bug was
                                         * that the first accounts or transfers controller to
                                         * land would have been open to a password bank staff
                                         * issued and still know.
                                         *
                                         * Naming the role here means a new endpoint is closed
                                         * to that state by default instead of relying on
                                         * whoever writes it to remember.
                                         */
                                        .anyRequest()
                                        .hasRole("CUSTOMER"))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(properties.allowedOrigins()));
        configuration.setAllowedMethods(List.copyOf(properties.allowedMethods()));
        configuration.setAllowedHeaders(List.copyOf(properties.allowedHeaders()));
        configuration.setExposedHeaders(List.copyOf(properties.exposedHeaders()));
        configuration.setAllowCredentials(properties.allowCredentials());
        configuration.setMaxAge(properties.maxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
