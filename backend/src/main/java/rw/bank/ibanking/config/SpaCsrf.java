package rw.bank.ibanking.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The two pieces a cookie-and-header CSRF handshake needs for a single-page app.
 *
 * <p>Neither is optional, and without them CSRF protection does not fail open — it fails
 * CLOSED, which is worse to debug: every authenticated POST from the portal returns 403 and
 * nothing explains why. Both defaults are correct for a server-rendered application and
 * wrong for this one.
 *
 * <ol>
 *   <li>{@link Cookie} forces the token to be written. Spring defers loading it, so the
 *       {@code XSRF-TOKEN} cookie is only set if something actually reads the token during
 *       the request — and on an API nothing does, so the browser never receives one and has
 *       nothing to echo back.
 *   <li>{@link Handler} makes the cookie's value usable as a header. The default masks the
 *       token per request to defend against BREACH, but it saves the RAW value to the
 *       cookie, so a client that reads the cookie and sends it verbatim is rejected. This
 *       keeps the masking where it helps and accepts the raw value in the header.
 * </ol>
 *
 * <p>The cookie is readable by JavaScript, deliberately, and that is not the hole it looks
 * like: the CSRF token is not a credential. It proves a request came from a page that could
 * read this origin, which is precisely what a cross-site attacker cannot do. The session
 * cookie stays HttpOnly.
 */
final class SpaCsrf {

    private SpaCsrf() {}

    /**
     * Reads the token so the repository writes the cookie.
     *
     * <p>Registered after Spring's own CSRF filter, which by then has put a deferred token
     * in the request. Calling {@code getToken()} is the whole job.
     */
    static final class Cookie extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {

            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                // Not a no-op: this is what triggers the Set-Cookie.
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * Masks the token when rendering it, accepts it raw from a header.
     *
     * <p>A form parameter still goes through the masking handler, because a token in a
     * form body came from a page this server rendered and the BREACH defence applies to it.
     * A header could only have been set by script on this origin.
     */
    static final class Handler implements CsrfTokenRequestHandler {

        private final CsrfTokenRequestHandler masked = new XorCsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();

        @Override
        public void handle(
                HttpServletRequest request,
                HttpServletResponse response,
                Supplier<CsrfToken> token) {
            masked.handle(request, response, token);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
            return request.getHeader(token.getHeaderName()) != null
                    ? plain.resolveCsrfTokenValue(request, token)
                    : masked.resolveCsrfTokenValue(request, token);
        }
    }
}
