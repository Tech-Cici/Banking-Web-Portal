package rw.bank.ibanking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Authentication for the bank's own endpoints.
 *
 * <p>A SECOND filter chain, ordered ahead of the main one, matching only the staff paths.
 * The main chain in {@link SecurityConfig} carries a deliberate posture — deny by default,
 * HTTP Basic disabled, OAuth2 resource server planned for the phase that introduces
 * customer sessions — and this does not disturb any of it.
 *
 * <p>HTTP Basic here, and that is a considered stopgap rather than the destination:
 *
 * <ul>
 *   <li>It is stateless, which matches the service's existing posture, so it introduces no
 *       session store and no CSRF surface.
 *   <li>It is enough to protect and test the staff endpoints and the four-eyes rule, which
 *       is the behaviour worth getting right first.
 *   <li>It is NOT what the staff portal expects. That UI signs in against a cookie session
 *       and the real answer is the OIDC architecture the blueprint specifies. Inventing a
 *       bespoke session scheme for a bank in the meantime would be worse than an honest
 *       placeholder, because it is the kind of thing that survives into production.
 * </ul>
 *
 * <p>Staff sign-in has no second factor, no device binding and no IP restriction. Staff
 * credentials reach other people's accounts, so they warrant more verification than a
 * customer's, not less. That gap is tracked in frontend/docs/OPEN-ITEMS.md and must close
 * before this goes live.
 */
@Configuration
class StaffSecurityConfig {

    /**
     * BCrypt, for staff and customer passwords alike.
     *
     * <p>Deliberately slow, which is the whole point for a credential someone chose: it
     * makes an offline attack against a stolen hash expensive. Note the contrast with
     * verification codes, which are hashed with SHA-256 for reasons documented in
     * {@code VerificationCodes} — the two are different problems.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain staffSecurityFilterChain(
            HttpSecurity http,
            UserDetailsService staffUserDetailsService,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            CorsConfigurationSource corsConfigurationSource)
            throws Exception {

        return http.securityMatcher("/api/v1/admin/**", "/api/v1/auth/staff/session")
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Stateless with no cookie, so there is no CSRF vector to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                /*
                 * IF_REQUIRED, not STATELESS: the staff portal signs in at
                 * /auth/staff/login and then relies on the session cookie, exactly as the
                 * customer portal does. HTTP Basic is kept alongside it so curl and the
                 * tests can authenticate without a login round-trip.
                 */
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .userDetailsService(staffUserDetailsService)
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(
                        handling ->
                                handling
                                        .authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }
}
