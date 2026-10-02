package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import rw.bank.ibanking.onboarding.domain.SignInMethod;
import rw.bank.ibanking.onboarding.domain.TrustedDeviceEntity;

/**
 * THE CUSTOMER'S OWN SECURITY PAGE, which until now had no server behind it.
 *
 * <p>THE HISTORY, and it decides what the first test is. The portal shipped a complete
 * security screen — trusted browsers with a revoke button, a sign-in history, a
 * change-password form — calling {@code /security/devices}, {@code /security/events} and
 * {@code /security/password}. None of the three existed. MSW answered all of them from
 * arrays that start empty on every page load, so the history showed nothing whatever had
 * happened to the account.
 *
 * <p>AND THE HISTORY WAS WORSE THAN ABSENT. Each row carried a location, and all four call
 * sites that wrote one passed the literal "Kigali, Rwanda" — there has never been a geo-IP
 * lookup in this service. The dashboard printed it to every customer in the world. The
 * second test here is the one that would have caught that, and it is written as an
 * assertion that the string cannot come back.
 */
@DisplayName("The customer's security page")
class SecurityPageTest extends OnboardingIntegrationTest {

    private static final String ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4001111111111","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"5000"}]}""";

    private static final String STRONG = "Correct-Horse-9-Battery";

    /**
     * The password {@code customerWithAccounts} leaves the customer holding.
     *
     * <p>The base class replaces the temporary password with this on its way through, so it
     * is the "current" one every test here types. Named rather than repeated so that
     * changing it in one place does not quietly break every assertion below.
     */
    private static final String CURRENT = "ZigamaCustomer9!";

    /* ------------------------------------------------------------- history */

    @Test
    @DisplayName("shows the sign-ins that actually happened")
    void reportsRealSignIns() throws Exception {
        MockHttpSession session = customerWithAccounts("history@example.rw", ACCOUNTS);

        /*
         * ONE ROW, which is what the helper produces: it signs in with the temporary
         * password and then replaces it, and replacing a password is not a sign-in. The
         * figure is asserted exactly rather than as "at least one" — a history that
         * invented an extra row would be as misleading as one that dropped a real one.
         *
         * THE TEST THE OLD SCREEN COULD NEVER HAVE PASSED. Signing in wrote rows that no
         * endpoint could read, so this list was empty however much had happened.
         */
        mvc.perform(get("/api/v1/security/events").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].at").isNotEmpty())
                /*
                 * THE METHOD IS THE ONE THE SERVICE TOOK, not a label the screen chose.
                 * This sign-in used the temporary password, which is the one most worth
                 * having in a customer's history: it was read off a staff screen and
                 * handed over on paper, so it is the credential most likely to have been
                 * used by somebody else first.
                 */
                .andExpect(jsonPath("$[0].method").value("The temporary password"));

        assertThat(signIns.findAll())
                .as("recorded, not composed at render time")
                .extracting(signIn -> signIn.method())
                .containsExactly(SignInMethod.TEMPORARY_PASSWORD);
    }

    @Test
    @DisplayName("never claims a location")
    void doesNotFabricateWhereTheCustomerWas() throws Exception {
        MockHttpSession session = customerWithAccounts("nowhere@example.rw", ACCOUNTS);

        String history =
                mvc.perform(get("/api/v1/security/events").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        /*
         * THE WHOLE POINT OF V19. "Kigali, Rwanda" was a string constant at four call
         * sites, and an earlier test in CustomerSignInTest asserted it — which is how a
         * fabricated value survives: a test defends it.
         *
         * Asserted against the raw body, not a field, so re-adding a location anywhere in
         * this response — under any key, as a default, inside a composed sentence — fails
         * here.
         */
        assertThat(history).doesNotContain("Kigali").doesNotContain("Rwanda");

        /* And the device is absent rather than invented: MockMvc sends no User-Agent. */
        mvc.perform(get("/api/v1/security/events").session(session))
                .andExpect(jsonPath("$[0].device").doesNotExist());
    }

    @Test
    @DisplayName("is nobody else's history")
    void isScopedToTheCaller() throws Exception {
        customerWithAccounts("theirs@example.rw", ACCOUNTS);
        MockHttpSession mine =
                customerWithAccounts(
                        "mine@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4002222222222","accountType":"CURRENT",\
                        "currency":"RWF","openingBalance":"5000"}]}""");

        /*
         * Two customers, one sign-in each. The caller is taken from the session and there
         * is no customer id anywhere in this API — a security page that accepted one would
         * read somebody else's history from an edited URL.
         */
        assertThat(signIns.findAll()).hasSize(2);

        mvc.perform(get("/api/v1/security/events").session(mine))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /* ------------------------------------------------------------- devices */

    @Test
    @DisplayName("lists the browsers the customer has trusted")
    void listsTrustedBrowsers() throws Exception {
        MockHttpSession session = customerWithAccounts("devices@example.rw", ACCOUNTS);
        UUID customerId = customers.findByEmailIgnoreCase("devices@example.rw").orElseThrow().id();

        trustedDevices.save(
                TrustedDeviceEntity.trusted(
                        customerId, "b".repeat(64), Duration.ofDays(30), "Chrome on Windows"));

        mvc.perform(get("/api/v1/security/devices").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].device").value("Chrome on Windows"))
                .andExpect(jsonPath("$[0].trustedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$[0].revoked").value(false))
                /*
                 * NOT CURRENT, because this request carries no device cookie for it. The
                 * flag is computed from the cookie, so a client cannot assert it — which
                 * is what stops somebody marking the browser they want to revoke as
                 * something else.
                 */
                .andExpect(jsonPath("$[0].current").value(false))
                /* And no token, nor its hash, in the response. See V14. */
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    @DisplayName("the revoke button revokes")
    void revokesOneBrowser() throws Exception {
        MockHttpSession session = customerWithAccounts("revoke@example.rw", ACCOUNTS);
        UUID customerId = customers.findByEmailIgnoreCase("revoke@example.rw").orElseThrow().id();

        var device =
                trustedDevices.save(
                        TrustedDeviceEntity.trusted(
                                customerId, "c".repeat(64), Duration.ofDays(30), "Safari on Mac"));

        mvc.perform(delete("/api/v1/security/devices/" + device.id()).session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(trustedDevices.findById(device.id()).orElseThrow().isRevoked()).isTrue();

        /*
         * STILL IN THE LIST, labelled. V14 keeps revoked rows because "this browser was
         * trusted and then it was not" is what somebody investigating an unauthorised
         * sign-in needs — and because a customer who just revoked something should be able
         * to see that it took.
         */
        mvc.perform(get("/api/v1/security/devices").session(session))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revoked").value(true))
                .andExpect(jsonPath("$[0].revokedReason").isNotEmpty());
    }

    @Test
    @DisplayName("will not revoke somebody else's browser")
    void refusesAnotherCustomersDevice() throws Exception {
        MockHttpSession mine = customerWithAccounts("attacker@example.rw", ACCOUNTS);
        customerWithAccounts(
                "victim@example.rw",
                """
                {"accounts":[{"accountNumber":"4003333333333","accountType":"CURRENT",\
                "currency":"RWF","openingBalance":"5000"}]}""");

        UUID victimId = customers.findByEmailIgnoreCase("victim@example.rw").orElseThrow().id();
        var theirs =
                trustedDevices.save(
                        TrustedDeviceEntity.trusted(
                                victimId, "d".repeat(64), Duration.ofDays(30), "Firefox on Linux"));

        /*
         * BREAK THE GUARD TO SEE THIS FAIL: drop the customer filter in
         * TrustedDevices.revokeOne and this becomes a 204 — a denial of service against
         * another customer, delivered by changing a UUID in a URL. 404 rather than 403,
         * because "that row exists but is not yours" is more than they should learn.
         */
        mvc.perform(delete("/api/v1/security/devices/" + theirs.id()).session(mine).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(trustedDevices.findById(theirs.id()).orElseThrow().isRevoked()).isFalse();
    }

    /* ------------------------------------------------------------ password */

    @Test
    @DisplayName("changing the password signs every remembered browser out")
    void changingThePasswordRevokesTrustedBrowsers() throws Exception {
        MockHttpSession session = customerWithAccounts("change@example.rw", ACCOUNTS);
        UUID customerId = customers.findByEmailIgnoreCase("change@example.rw").orElseThrow().id();

        trustedDevices.save(
                TrustedDeviceEntity.trusted(
                        customerId, "e".repeat(64), Duration.ofDays(30), "Chrome on Android"));
        trustedDevices.save(
                TrustedDeviceEntity.trusted(
                        customerId, "f".repeat(64), Duration.ofDays(30), "Edge on Windows"));

        outbox.deleteAll();

        /*
         * THE ASSERTION WORTH HAVING. A customer changing their password because they fear
         * somebody has it has not finished the job if every browser that person trusted
         * still signs in on the new password without a code. The count comes back so the
         * screen can say so — otherwise the next sign-in asks for a code with no
         * explanation.
         */
        mvc.perform(
                        post("/api/v1/security/password")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s","newPassword":"%s"}"""
                                                .formatted(CURRENT, STRONG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.browsersSignedOut").value(2));

        assertThat(trustedDevices.findByCustomerId(customerId))
                .allMatch(TrustedDeviceEntity::isRevoked);

        /* And the customer is told, which is the only way somebody whose password was
           changed BY SOMEBODY ELSE finds out. */
        assertThat(outbox.findAll())
                .extracting(message -> message.kind())
                .contains(OutboxKind.PASSWORD_CHANGED);
    }

    @Test
    @DisplayName("refuses a wrong current password")
    void refusesAWrongCurrentPassword() throws Exception {
        MockHttpSession session = customerWithAccounts("wrong@example.rw", ACCOUNTS);

        /*
         * Without this check a session left open on a shared machine is a session that can
         * lock its owner out, and whoever did it would not need to know the password.
         */
        mvc.perform(
                        post("/api/v1/security/password")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"not-my-password","newPassword":"%s"}"""
                                                .formatted(STRONG)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("applies the same password policy as the first-time change")
    void refusesAWeakNewPassword() throws Exception {
        MockHttpSession session = customerWithAccounts("weak@example.rw", ACCOUNTS);

        /* One method, CustomerAuthService.passwordProblem, so the two screens cannot come
           to disagree about what a password must be. */
        mvc.perform(
                        post("/api/v1/security/password")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s","newPassword":"short"}"""
                                                .formatted(CURRENT)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("is closed without a session")
    void requiresASession() throws Exception {
        /*
         * The catch-all in SecurityConfig is hasRole("CUSTOMER"), so these endpoints are
         * closed by default rather than because somebody remembered to list them — which
         * is the property that rule exists for. Asserted so that a future change to it
         * cannot quietly open a customer's sign-in history.
         */
        mvc.perform(get("/api/v1/security/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/security/devices")).andExpect(status().isUnauthorized());
    }
}
