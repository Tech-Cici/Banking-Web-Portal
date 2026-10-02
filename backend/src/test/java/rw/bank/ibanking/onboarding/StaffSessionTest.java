package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * The staff endpoints, driven through a COOKIE SESSION rather than HTTP Basic.
 *
 * <p>This exists because of a bug that every other test in the suite was blind to.
 *
 * <p>{@link StaffOnboardingTest} authenticates with {@code httpBasic(...)}, which puts a
 * Spring {@code UserDetails} object in the security principal. The staff portal does not:
 * it posts to {@code /auth/staff/login} and gets a session whose principal is the
 * member's email as a plain String. Three controller methods took
 * {@code @AuthenticationPrincipal UserDetails}, so under a real sign-in that parameter
 * bound to null and the first call on it threw — while eleven passing tests said the
 * endpoints worked.
 *
 * <p>The lesson generalises: a test that authenticates differently from the application
 * is not testing the application's authentication. So this class signs in exactly as the
 * portal does and never touches HTTP Basic.
 */
class StaffSessionTest extends OnboardingIntegrationTest {

    private static final String ADMIN = "admin@zigama.local";
    private static final String MANAGER = "manager@zigama.local";
    private static final String PASSWORD = "ZigamaStaff1";

    /** Signs in the way the portal does, and returns the session it was given. */
    private MockHttpSession signIn(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/staff/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}
                                        """
                                                .formatted(email, PASSWORD)))
                .andExpect(status().isOk());
        return session;
    }
    /* ------------------------------------------------------------- tests */

    @Test
    @DisplayName("the whole staff chain works on a session, not just on HTTP Basic")
    void chainOverASession() throws Exception {
        String applicationId = registerApplicant("session@example.rw");

        MockHttpSession adminSession = signIn(ADMIN);

        // Reads.
        mvc.perform(get("/api/v1/admin/applications").session(adminSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/summary").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(1));

        /*
         * The write that was broken. It needs the caller's identity to record WHO created
         * the account, which is the whole basis of the four-eyes rule — so an endpoint
         * that cannot resolve the caller is not a small fault.
         */
        JsonNode created =
                json.readTree(
                        mvc.perform(
                                        post(
                                                        "/api/v1/admin/applications/{id}/create-account",
                                                        applicationId)
                                                .session(adminSession)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(ONE_CURRENT_ACCOUNT))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        String customerId = created.get("customer").get("id").asString();
        assertThat(created.get("customer").get("createdByName").asString())
                .as("the session's identity must reach the record")
                .isEqualTo("Jean Claude Nkurunziza");

        // And the manager's step, on their own session.
        MockHttpSession managerSession = signIn(MANAGER);

        mvc.perform(post("/api/v1/admin/customers/{id}/approve", customerId).session(managerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.approvedByName").value("Immaculee Mukandayisenga"));
    }

    @Test
    @DisplayName("four eyes still holds when both are sessions")
    void fourEyesOverSessions() throws Exception {
        String applicationId = registerApplicant("foureyes@example.rw");
        MockHttpSession adminSession = signIn(ADMIN);

        JsonNode created =
                json.readTree(
                        mvc.perform(
                                        post(
                                                        "/api/v1/admin/applications/{id}/create-account",
                                                        applicationId)
                                                .session(adminSession)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(ONE_CURRENT_ACCOUNT))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        /*
         * The administrator who created it cannot also release it. Worth asserting on the
         * session path too: the rule is enforced by comparing names, and the name is
         * exactly what the session path was failing to produce.
         */
        mvc.perform(
                        post(
                                        "/api/v1/admin/customers/{id}/approve",
                                        created.get("customer").get("id").asString())
                                .session(adminSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a session cannot do the other role's job")
    void rolesHoldOverSessions() throws Exception {
        String applicationId = registerApplicant("roles@example.rw");
        MockHttpSession managerSession = signIn(MANAGER);

        mvc.perform(
                        post("/api/v1/admin/applications/{id}/create-account", applicationId)
                                .session(managerSession)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ONE_CURRENT_ACCOUNT))
                .andExpect(status().isForbidden());

        assertThat(customers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("no session, no staff endpoints")
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/v1/admin/applications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the development reset works on a session too")
    void devResetOverASession() throws Exception {
        registerApplicant("wipesession@example.rw");
        assertThat(applications.findAll()).isNotEmpty();

        mvc.perform(post("/api/v1/admin/dev/reset").session(signIn(MANAGER)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/dev/reset").session(signIn(ADMIN)))
                .andExpect(status().isNoContent());

        assertThat(applications.findAll()).isEmpty();
    }
}
