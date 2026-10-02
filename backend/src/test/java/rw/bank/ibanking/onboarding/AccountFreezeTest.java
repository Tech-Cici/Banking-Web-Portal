package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.bank.ibanking.onboarding.domain.CustomerStatus;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import tools.jackson.databind.JsonNode;

/**
 * Freezing an account, and what that has to mean.
 *
 * <p>The test that matters most here is {@link #freezingEndsTheLiveSession()}. Before this
 * feature, {@code canSignIn()} was consulted only when a customer signed IN — so a session
 * created a minute earlier kept working whatever the account's status became. A freeze
 * would have stopped the next sign-in and not the person already inside the account, which
 * is precisely backwards for the case the feature exists to handle.
 */
class AccountFreezeTest extends OnboardingIntegrationTest {

    private static final String ADMIN = "admin@zigama.local";
    private static final String MANAGER = "manager@zigama.local";
    private static final String STAFF_PASSWORD = "ZigamaStaff1";
    private static final String CHOSEN_PASSWORD = "Umutekano2026!";

    private MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
        return request.with(httpBasic(user, STAFF_PASSWORD));
    }

    private String reason(String text) {
        return """
               {"reason":"%s"}
               """.formatted(text);
    }

    /* ----------------------------------------------------------- fixtures */

    /** A customer who is ACTIVE with a password of their own, and their id. */
    private String signedUpCustomer(String email) throws Exception {
        JsonNode challenge =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/registration/personal/start")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"accountNumber":"1234567890",
                                                         "nationalId":"1199570099999999",
                                                         "dateOfBirth":"1990-01-01",
                                                         "phone":"0781999888","email":"%s",
                                                         "fullName":"Test Applicant"}
                                                        """
                                                                .formatted(email)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        JsonNode verified =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/registration/personal/verify")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"challengeId":"%s","code":"%s"}
                                                        """
                                                                .formatted(
                                                                        challenge
                                                                                .get("challengeId")
                                                                                .asString(),
                                                                        codeIn(email))))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        mvc.perform(
                        post("/api/v1/registration/personal/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"verificationToken":"%s"}
                                        """
                                                .formatted(
                                                        verified
                                                                .get("verificationToken")
                                                                .asString())))
                .andExpect(status().isCreated());

        String applicationId = applications.findAll().get(0).id().toString();

        JsonNode created =
                json.readTree(
                        mvc.perform(
                                        as(
                                                ADMIN,
                                                post(
                                                        "/api/v1/admin/applications/{id}/create-account",
                                                        applicationId)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(ONE_CURRENT_ACCOUNT)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        String customerId = created.get("customer").get("id").asString();

        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk());

        // Settle the temporary password, so the customer has one of their own.
        String temporary = emailedPassword(email);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}
                                        """
                                                .formatted(email, temporary)))
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        mvc.perform(
                        post("/api/v1/auth/password/change-temporary")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s","newPassword":"%s"}
                                        """
                                                .formatted(temporary, CHOSEN_PASSWORD)))
                .andExpect(status().isNoContent());

        return customerId;
    }

    private String codeIn(String email) {
        var message = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).get(0);
        var matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(message.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String emailedPassword(String email) {
        var message =
                outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                        .filter(m -> m.kind() == OutboxKind.ACCOUNT_APPROVED)
                        .findFirst()
                        .orElseThrow();
        var matcher = Pattern.compile("Temporary password: ([A-Z2-9-]+)").matcher(message.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** Signs the customer fully in, code and all, and returns the session. */
    private MockHttpSession signIn(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        JsonNode challenge =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/auth/login")
                                                .session(session)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"identifier":"%s","password":"%s"}
                                                        """
                                                                .formatted(
                                                                        email, CHOSEN_PASSWORD)))
                                .andExpect(jsonPath("$.outcome").value("CHALLENGE_REQUIRED"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        mvc.perform(
                        post("/api/v1/auth/verify")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"challengeId":"%s","code":"%s"}
                                        """
                                                .formatted(
                                                        challenge
                                                                .get("challenge")
                                                                .get("challengeId")
                                                                .asString(),
                                                        codeIn(email))))
                .andExpect(status().isOk());

        return session;
    }

    /* --------------------------------------------------------------- tests */

    @Test
    @DisplayName("freezing ends a session that is already open")
    void freezingEndsTheLiveSession() throws Exception {
        String email = "frozenlive@example.rw";
        String customerId = signedUpCustomer(email);
        MockHttpSession session = signIn(email);

        // Signed in and working.
        mvc.perform(get("/api/v1/session").session(session)).andExpect(status().isOk());

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Suspected compromise reported by the customer.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        /*
         * THE POINT OF THE WHOLE FEATURE.
         *
         * The same cookie, on the same session, a moment later. If this returns 200 the
         * freeze is decorative: a manager acting on a compromised login would have
         * stopped the next sign-in and left whoever is already inside untouched.
         */
        mvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isUnauthorized());

        // And the session is destroyed, not merely refused once.
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("a frozen customer cannot sign in again either")
    void frozenCannotSignIn() throws Exception {
        String email = "frozenlogin@example.rw";
        String customerId = signedUpCustomer(email);

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Card reported stolen.")))
                .andExpect(status().isOk());

        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}
                                        """
                                                .formatted(email, CHOSEN_PASSWORD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("suspended")));
    }

    @Test
    @DisplayName("unfreezing gives the account back, with the customer's own password")
    void unfreezingRestoresAccess() throws Exception {
        String email = "thawed@example.rw";
        String customerId = signedUpCustomer(email);

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Precautionary, pending a call back.")))
                .andExpect(status().isOk());

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/unfreeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Customer confirmed the activity was theirs.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        /*
         * The password they chose still works. A freeze does not destroy the credential,
         * so a precautionary freeze does not turn into a password reissue for every
         * customer it touches.
         */
        outbox.deleteAll();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}
                                        """
                                                .formatted(email, CHOSEN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CHALLENGE_REQUIRED"));
    }

    /* ------------------------------------------------------- who may do it */

    @Test
    @DisplayName("an administrator cannot freeze — it is the manager's decision")
    void adminCannotFreeze() throws Exception {
        String customerId = signedUpCustomer("notadmin@example.rw");

        mvc.perform(
                        as(ADMIN, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Trying it on.")))
                .andExpect(status().isForbidden());

        assertThat(customers.findAll().get(0).status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("nobody unauthenticated can freeze anything")
    void anonymousCannotFreeze() throws Exception {
        String customerId = signedUpCustomer("anon@example.rw");

        mvc.perform(
                        post("/api/v1/admin/customers/{id}/freeze", customerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("No.")))
                .andExpect(status().isUnauthorized());

        assertThat(customers.findAll().get(0).status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    /* ----------------------------------------------------- the rules of it */

    @Test
    @DisplayName("a freeze needs a reason, and nothing happens without one")
    void reasonIsRequired() throws Exception {
        String customerId = signedUpCustomer("noreason@example.rw");

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("   ")))
                .andExpect(status().isBadRequest());

        assertThat(customers.findAll().get(0).status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(
                        outbox.findAll().stream()
                                .noneMatch(m -> m.kind() == OutboxKind.ACCOUNT_FROZEN))
                .as("no email goes out for a freeze that did not happen")
                .isTrue();
    }

    @Test
    @DisplayName("freezing twice is refused, and so is unfreezing what is not frozen")
    void transitionsAreGuarded() throws Exception {
        String customerId = signedUpCustomer("twice@example.rw");

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/unfreeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("It is not frozen.")))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("First time.")))
                .andExpect(status().isOk());

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Second time.")))
                .andExpect(status().isUnprocessableContent());
    }

    /* ------------------------------------------------- what the customer hears */

    @Test
    @DisplayName("the customer is told it happened, but never why")
    void theReasonStaysWithTheBank() throws Exception {
        String email = "discreet@example.rw";
        String customerId = signedUpCustomer(email);
        String secret = "Flagged in an ongoing fraud investigation.";

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason(secret)))
                .andExpect(status().isOk());

        var mail =
                outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                        .filter(m -> m.kind() == OutboxKind.ACCOUNT_FROZEN)
                        .findFirst()
                        .orElseThrow();

        /*
         * The reason must not travel. A freeze may concern an investigation the customer
         * must not be tipped off about, and this service cannot tell which kind it is
         * looking at — so it never says.
         */
        assertThat(mail.body()).doesNotContain(secret);
        assertThat(mail.body()).doesNotContain("fraud");
        assertThat(mail.body()).doesNotContain("investigation");

        // But it does tell them what they need: that it happened, and who to ask.
        assertThat(mail.body()).contains("paused");
        assertThat(mail.body()).contains("call the number on the back of your card");
        assertThat(mail.body()).contains("Your money is not affected");

        // Staff, on the other hand, can see exactly why.
        mvc.perform(as(MANAGER, get("/api/v1/admin/customers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].freezeReason").value(secret))
                .andExpect(jsonPath("$[0].frozenBy").value("Immaculee Mukandayisenga"))
                .andExpect(jsonPath("$[0].frozenAt").isNotEmpty());
    }

    @Test
    @DisplayName("the history survives being unfrozen")
    void historyIsNotErased() throws Exception {
        String customerId = signedUpCustomer("history@example.rw");

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/freeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Suspicious sign-in from an unknown device.")))
                .andExpect(status().isOk());

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/unfreeze", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reason("Confirmed by the customer on the phone.")))
                .andExpect(status().isOk());

        /*
         * Restoring access must not erase the fact that access was once removed. An
         * account frozen and unfrozen repeatedly is a pattern worth being able to see.
         */
        var customer = customers.findAll().get(0);
        assertThat(customer.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(customer.frozenAt()).isNotNull();
        assertThat(customer.freezeReason()).contains("Confirmed by the customer");
    }
}
