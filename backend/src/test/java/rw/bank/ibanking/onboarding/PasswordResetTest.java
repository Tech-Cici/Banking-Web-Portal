package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import rw.bank.ibanking.onboarding.domain.PasswordResetStatus;

/**
 * A CUSTOMER WHO HAS FORGOTTEN THEIR PASSWORD.
 *
 * <p>The flow has no reset link and no token: the customer asks, a manager sees the
 * request, and the manager re-issues a temporary password through the same path used at
 * approval. V15 records why.
 *
 * <p>The assertions worth having are not "the endpoint returns 202". They are the four
 * properties that make this safe, each of which can be broken by a plausible edit:
 *
 * <ul>
 *   <li>The answer does not say whether the address banks here.
 *   <li>Asking creates no credential, so a stranger cannot lock somebody out.
 *   <li>Issuing one produces a password that WORKS and lands on the change-password step.
 *   <li>Issuing one withdraws the browsers that were allowed to skip the emailed code.
 * </ul>
 */
@DisplayName("A forgotten password")
class PasswordResetTest extends OnboardingIntegrationTest {

    private static final String PASSWORD = "ZigamaCustomer9!";

    private static final String ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4009999999999","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"1000"}]}""";

    private void ask(String identifier) throws Exception {
        mvc.perform(
                        post("/api/v1/auth/password/forgot")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identifier":"%s"}""".formatted(identifier)))
                /*
                 * 202 AND NOTHING ELSE. Asserted on every call in this class, including the
                 * ones for addresses that do not exist, because the identical response is
                 * the security property — not a detail of the status code.
                 */
                .andExpect(status().isAccepted())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    private String customerIdOf(String email) {
        return customers.findByEmailIgnoreCase(email).orElseThrow().id().toString();
    }

    /** The newest message to this address. */
    private String lastMailTo(String email) {
        var mail = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email);
        assertThat(mail).as("a message should have been sent to %s", email).isNotEmpty();
        return mail.get(0).body();
    }

    /* ============================================================ asking */

    @Test
    @DisplayName("AN UNKNOWN ADDRESS LOOKS EXACTLY LIKE A REAL ONE, and records nothing")
    void anUnknownAddressIsIndistinguishable() throws Exception {
        String email = "forgetful@example.rw";
        customerWithAccounts(email, ACCOUNTS);

        int mailBefore = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).size();

        // Both calls assert the same 202 with an empty body inside ask().
        ask("nobody-at-all@example.rw");
        ask(email);

        /*
         * NOTHING WAS WRITTEN FOR THE STRANGER. One row, for the real customer. A table
         * that also held the guess would be a list of addresses that are not customers,
         * and the queue screen would show it to staff.
         */
        assertThat(passwordRequests.findAll()).hasSize(1);
        assertThat(passwordRequests.findAll().get(0).customerId().toString())
                .isEqualTo(customerIdOf(email));

        /*
         * AND NO EMAIL WENT OUT FOR EITHER. Asking is not an event the customer needs
         * telling about — they just did it — and a "somebody asked to reset your password"
         * message to a real customer whose address a stranger typed is a notification the
         * bank sends on somebody else's command.
         */
        assertThat(outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email)).hasSize(mailBefore);
        assertThat(outbox.findByRecipientIgnoreCaseOrderBySentAtDesc("nobody-at-all@example.rw"))
                .isEmpty();
    }

    @Test
    @DisplayName("ASKING TAKES NOTHING AWAY — the existing password still works")
    void askingDoesNotLockAnybodyOut() throws Exception {
        String email = "still-works@example.rw";
        customerWithAccounts(email, ACCOUNTS);

        /*
         * The attack this rules out: type somebody's address into the public form and
         * leave them unable to sign in. The request must create no credential and
         * invalidate none.
         */
        ask(email);

        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(new MockHttpSession())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identifier":"%s","password":"%s"}"""
                                        .formatted(email, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ASKING TWICE DOES NOT QUEUE IT TWICE")
    void askingTwiceRaisesOneRequest() throws Exception {
        String email = "impatient@example.rw";
        customerWithAccounts(email, ACCOUNTS);

        ask(email);
        ask(email);
        ask(email);

        /*
         * One row, and — just as importantly — the repeats were not refused. A customer
         * clicking again has done nothing wrong, and an error on the second click would
         * confirm that this address banks here.
         */
        assertThat(passwordRequests.findAll()).hasSize(1);

        mvc.perform(asStaff(MANAGER_EMAIL, get("/api/v1/admin/password-requests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /* ========================================================= the manager */

    @Test
    @DisplayName("A MANAGER ISSUES A PASSWORD THAT ACTUALLY WORKS, and it must be replaced")
    void theIssuedPasswordWorks() throws Exception {
        String email = "reissue@example.rw";
        customerWithAccounts(email, ACCOUNTS);
        ask(email);

        String requestId = passwordRequests.findAll().get(0).id().toString();

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/password-requests/{id}/issue", requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                /* The response carries no credential, and never should. */
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.temporaryPassword").doesNotExist());

        assertThat(passwordRequests.findAll().get(0).status())
                .isEqualTo(PasswordResetStatus.FULFILLED);
        assertThat(passwordRequests.findAll().get(0).settledByName()).isNotBlank();

        /*
         * THE EMAIL CARRIES IT, in the same labelled form as the approval message — the
         * shape a customer has seen before is the shape a phishing message cannot borrow
         * credibility from.
         */
        String mail = lastMailTo(email);
        var matcher = Pattern.compile("Temporary password: (\\S+)").matcher(mail);
        assertThat(matcher.find()).as("the email should carry the new password").isTrue();
        String issued = matcher.group(1);

        assertThat(mail)
                .as("and it should say what to do if the customer did not ask")
                .contains("IF YOU DID NOT ASK FOR THIS");

        /*
         * THE ASSERTION THAT MATTERS. An outcome string is not a session and a stored hash
         * is not a working password: signing in with the issued credential has to land on
         * the change-password step, because that is the state the whole flow depends on.
         */
        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identifier":"%s","password":"%s"}"""
                                        .formatted(email, issued)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        /* And the portal is shut until it has been replaced. */
        mvc.perform(get("/api/v1/accounts").session(session)).andExpect(status().isForbidden());

        mvc.perform(
                        post("/api/v1/auth/password/change-temporary")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s","newPassword":"BrandNewOne9!"}"""
                                                .formatted(issued)))
                .andExpect(status().isNoContent());

        /* The old password is dead. */
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(new MockHttpSession())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"identifier":"%s","password":"%s"}"""
                                        .formatted(email, PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ISSUING ONE WITHDRAWS THE TRUSTED BROWSERS")
    void issuingRevokesTrustedDevices() throws Exception {
        String email = "had-a-trusted-browser@example.rw";
        customerWithAccounts(email, ACCOUNTS);

        /*
         * A browser this customer had already proved. Written directly rather than walked
         * through the code step, because what is under test is the revocation and not how
         * the trust was obtained.
         */
        trustedDevices.save(
                rw.bank.ibanking.onboarding.domain.TrustedDeviceEntity.trusted(
                        customers.findByEmailIgnoreCase(email).orElseThrow().id(),
                        "a".repeat(64),
                        java.time.Duration.ofDays(30),
                        /* No label, which is a legitimate row: a client that sends no
                           User-Agent gets no description. See TrustedDeviceEntity.device. */
                        null));

        assertThat(trustedDevices.findAll()).hasSize(1);
        assertThat(trustedDevices.findAll().get(0).isRevoked()).isFalse();

        ask(email);
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post(
                                        "/api/v1/admin/password-requests/{id}/issue",
                                        passwordRequests.findAll().get(0).id().toString())))
                .andExpect(status().isOk());

        /*
         * A reset is what the bank does when somebody may have a customer's password. A
         * browser still entitled to skip the emailed code would be the one door it left
         * open, and nobody would notice until it was used.
         */
        assertThat(trustedDevices.findAll().get(0).isRevoked()).isTrue();
        assertThat(lastMailTo(email))
                .as("and the customer is told, so being asked for a code again is not a fault")
                .contains("set to skip the sign-in code have been reset");
    }

    @Test
    @DisplayName("ONLY A MANAGER MAY ISSUE ONE — an administrator is refused by the server")
    void anAdministratorCannotIssueAPassword() throws Exception {
        String email = "admin-cannot@example.rw";
        customerWithAccounts(email, ACCOUNTS);
        ask(email);

        String requestId = passwordRequests.findAll().get(0).id().toString();

        /*
         * The role that can GIVE access is the role that can give it back. An
         * administrator creates accounts; a manager releases them, and a re-issued
         * password is a release.
         */
        mvc.perform(
                        asStaff(
                                ADMIN_EMAIL,
                                post("/api/v1/admin/password-requests/{id}/issue", requestId)))
                .andExpect(status().isForbidden());

        mvc.perform(asStaff(ADMIN_EMAIL, get("/api/v1/admin/password-requests")))
                .andExpect(status().isForbidden());

        /* And nothing happened. */
        assertThat(passwordRequests.findAll().get(0).status())
                .isEqualTo(PasswordResetStatus.PENDING);
    }

    @Test
    @DisplayName("A FROZEN CUSTOMER GETS NO PASSWORD, because it could not help them")
    void afrozenCustomerIsRefused() throws Exception {
        String email = "frozen@example.rw";
        customerWithAccounts(email, ACCOUNTS);
        String customerId = customerIdOf(email);
        ask(email);

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/customers/{id}/freeze", customerId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"reason":"Under review"}""")))
                .andExpect(status().isOk());

        /*
         * Sign-in is refused on status whatever password they hold, so issuing one would
         * send a credential that cannot work and move the confusion to the sign-in screen.
         */
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post(
                                        "/api/v1/admin/password-requests/{id}/issue",
                                        passwordRequests.findAll().get(0).id().toString())))
                .andExpect(status().isUnprocessableContent());

        assertThat(passwordRequests.findAll().get(0).status())
                .isEqualTo(PasswordResetStatus.PENDING);
    }

    @Test
    @DisplayName("REFUSING NEEDS A REASON, and the customer is told what it was")
    void refusingNeedsAReasonAndIsEmailed() throws Exception {
        String email = "refused@example.rw";
        customerWithAccounts(email, ACCOUNTS);
        ask(email);

        String requestId = passwordRequests.findAll().get(0).id().toString();
        String path = "/api/v1/admin/password-requests/{id}/refuse";

        /*
         * 400, not 422: the endpoint's own @NotBlank rejects a blank reason at the
         * boundary, before the service is reached. Asserted at the status the system
         * actually produces rather than the one the entity guard would give, because the
         * entity guard is the second line — it exists for a caller that bypasses the
         * controller, and both are worth having.
         */
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post(path, requestId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"reason":""}""")))
                .andExpect(status().isBadRequest());

        assertThat(passwordRequests.findAll().get(0).status())
                .isEqualTo(PasswordResetStatus.PENDING);

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post(path, requestId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"reason":"Could not confirm identity by telephone"}""")))
                .andExpect(status().isOk());

        assertThat(passwordRequests.findAll().get(0).status())
                .isEqualTo(PasswordResetStatus.REFUSED);

        /*
         * TOLD, NOT LEFT WAITING. A refused request with no message is a customer checking
         * an empty inbox for a week and then telephoning anyway.
         */
        String mail = lastMailTo(email);
        assertThat(mail).contains("Could not confirm identity by telephone");
        assertThat(mail).contains("has not been changed");
        assertThat(mail)
                .as("a refusal must not carry a credential")
                .doesNotContain("Temporary password:");
    }

    @Test
    @DisplayName("A SECOND MANAGER CANNOT SETTLE THE SAME REQUEST TWICE")
    void theSameRequestCannotBeSettledTwice() throws Exception {
        String email = "raced@example.rw";
        customerWithAccounts(email, ACCOUNTS);
        ask(email);

        String requestId = passwordRequests.findAll().get(0).id().toString();
        String issue = "/api/v1/admin/password-requests/{id}/issue";

        mvc.perform(asStaff(MANAGER_EMAIL, post(issue, requestId))).andExpect(status().isOk());

        /*
         * Two managers with the queue open would otherwise both issue a password, and the
         * second would silently invalidate the first — so the customer reads two emails and
         * the one they try first does not work. A visible conflict is the better failure.
         */
        mvc.perform(asStaff(MANAGER_EMAIL, post(issue, requestId)))
                .andExpect(status().isUnprocessableContent());

        assertThat(outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                        .filter(m -> m.body().contains("Temporary password:"))
                        .count())
                .as("exactly one password email, not two")
                .isEqualTo(2); // one at approval, one from the single successful re-issue
    }
}
