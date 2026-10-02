package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The CSRF handshake, driven the way the browser drives it.
 *
 * <p>Every other test in this suite uses {@code .with(csrf())}, which fabricates a valid
 * token from inside the server. That proves the endpoints are protected and proves nothing
 * about whether a real client can satisfy the protection — which is a different question,
 * and the one that was broken: Spring writes the raw token to the cookie but expects a
 * masked one in the header, so a client echoing the cookie is refused.
 *
 * <p>So this test never fabricates a token. It reads the {@code XSRF-TOKEN} cookie off a
 * response, exactly as the portal's fetch wrapper does, and sends it back in
 * {@code X-XSRF-TOKEN}.
 *
 * <p>{@code @DirtiesContext} IS LOAD-BEARING, and it is worth knowing why before anyone
 * removes it to save eleven seconds. {@code .with(csrf())} does not merely add a token to
 * one request: Spring Security's test support swaps the {@code CsrfTokenRepository} on the
 * live filter chain for a test one, and that swap stays in the cached application context
 * for every test class that runs afterwards. From then on no real {@code XSRF-TOKEN}
 * cookie is written, so the four tests below — the only ones that check the handshake a
 * browser actually performs — fail with a null cookie.
 *
 * <p>That is precisely the kind of failure this class exists to catch, arriving as a false
 * alarm: it looked like CSRF had broken in the application when it had broken only in the
 * test fixture. It stayed hidden for months because Surefire happened to run this class
 * before any class that fabricates a token, and it surfaced the day a new test class was
 * added whose name sorts earlier. Asking for a fresh context makes the order irrelevant.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CsrfHandshakeTest extends OnboardingIntegrationTest {

    private static final String COOKIE = "XSRF-TOKEN";
    private static final String HEADER = "X-XSRF-TOKEN";

    @Test
    @DisplayName("a plain request hands the browser a token to echo")
    void tokenCookieIsIssued() throws Exception {
        Cookie token =
                mvc.perform(get("/api/v1/health"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie(COOKIE);

        /*
         * The whole point of SpaCsrf.Cookie. Spring loads the token lazily, and on a JSON
         * API nothing reads it during a request, so without that filter this is null and
         * the client has nothing to send.
         */
        assertThat(token).as("the XSRF-TOKEN cookie").isNotNull();
        assertThat(token.getValue()).isNotBlank();

        /*
         * Readable by script, on purpose: the SPA has to read it. Safe in a way it would
         * not be for the session cookie, because a CSRF token is not a credential — it
         * only proves the request came from a page that can read this origin.
         */
        assertThat(token.isHttpOnly()).isFalse();
    }

    @Test
    @DisplayName("echoing the cookie in the header is accepted")
    void cookieValueIsAcceptedAsAHeader() throws Exception {
        MockHttpSession session = new MockHttpSession();

        Cookie token =
                mvc.perform(get("/api/v1/health").session(session))
                        .andReturn()
                        .getResponse()
                        .getCookie(COOKIE);
        assertThat(token).isNotNull();

        /*
         * A signed-out POST is enough to exercise the CSRF filter: it runs before
         * authorisation, so a rejected token produces 403 and an accepted one produces the
         * 401 that the endpoint itself would give. 401 here therefore means the token
         * passed — which is the assertion, awkward as it reads.
         */
        mvc.perform(
                        post("/api/v1/auth/password/change-temporary")
                                .session(session)
                                .cookie(token)
                                .header(HEADER, token.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"x","newPassword":"y"}
                                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong token is refused")
    void wrongTokenRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();

        Cookie token =
                mvc.perform(get("/api/v1/health").session(session))
                        .andReturn()
                        .getResponse()
                        .getCookie(COOKIE);
        assertThat(token).isNotNull();

        mvc.perform(
                        post("/api/v1/auth/password/change-temporary")
                                .session(session)
                                .cookie(token)
                                .header(HEADER, "not-the-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"x","newPassword":"y"}
                                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sign-in and registration need no token, or nobody could obtain one")
    void signInIsExempt() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"nobody@example.rw","password":"whatever1"}
                                        """))
                // 401 from the service, not 403 from the CSRF filter.
                .andExpect(status().isUnauthorized());

        mvc.perform(
                        post("/api/v1/registration/personal/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"accountNumber":"1234567890","nationalId":"1199570099999999",
                                         "dateOfBirth":"1990-01-01","phone":"0781999888",
                                         "email":"csrfexempt@example.rw","fullName":"Test Applicant"}
                                        """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the staff chain still takes HTTP Basic without a token")
    void staffChainUnaffected() throws Exception {
        /*
         * The staff chain disables CSRF because HTTP Basic carries no ambient credential
         * the browser attaches by itself. Asserted here so the change above cannot quietly
         * break the tests-and-curl path.
         */
        mvc.perform(
                        get("/api/v1/admin/applications")
                                .with(httpBasic("admin@zigama.local", "ZigamaStaff1")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the token survives the session change at sign-in")
    void tokenIsUsableAfterSessionFixationChange() throws Exception {
        /*
         * sessionFixation(newSession) replaces the session on sign-in. The CSRF token is
         * kept in a cookie rather than the session, so it survives — but if it were ever
         * moved to a session repository, the first POST after sign-in would start failing
         * and this is the test that would say so.
         */
        MockHttpSession session = new MockHttpSession();

        Cookie before =
                mvc.perform(get("/api/v1/health").session(session))
                        .andReturn()
                        .getResponse()
                        .getCookie(COOKIE);
        assertThat(before).isNotNull();

        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"nobody@example.rw","password":"whatever1"}
                                        """))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(
                        post("/api/v1/auth/logout")
                                .session(session)
                                .cookie(before)
                                .header(HEADER, before.getValue()))
                .andExpect(status().isNoContent());
    }
}
