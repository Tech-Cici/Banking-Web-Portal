package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import tools.jackson.databind.JsonNode;

/**
 * The customer's side: from the temporary password an administrator issued to a working
 * session.
 *
 * <p>Driven through the real filter chain with real cookies, because most of what is worth
 * testing here is what the SESSION is allowed to do rather than what the service methods
 * return. A service-level test would prove {@code login} produces
 * {@code PASSWORD_CHANGE_REQUIRED} and prove nothing about whether the session it hands out
 * can reach the dashboard.
 *
 * <p>The one that matters most is {@link #temporaryPasswordSessionIsRefusedElsewhere()}. A
 * temporary password is known to the member of staff who read it off their screen, so a
 * session built on one has to be useless for everything except replacing it — and that has
 * to be enforced by the server, not by a redirect the browser is free to ignore.
 */
class CustomerSignInTest extends OnboardingIntegrationTest {

    private static final String ADMIN = "admin@zigama.local";
    private static final String MANAGER = "manager@zigama.local";
    private static final String STAFF_PASSWORD = "ZigamaStaff1";

    /** Satisfies the rules in CustomerAuthService.passwordProblem. */
    private static final String CHOSEN_PASSWORD = "Umutekano2026!";

    /* ----------------------------------------------------------- fixtures */

    /**
     * An approved account, and the temporary password the bank EMAILED its owner.
     *
     * <p>The password is read out of the approval message, because that is now the only
     * place it exists — it is generated when a manager approves, not when an
     * administrator creates, and no endpoint returns it.
     */
    private record NewAccount(String customerId, String email, String temporaryPassword) {}

    private String createAccountFor(String email) throws Exception {
        String start =
                """
                {"accountNumber":"1234567890","nationalId":"1199570099999999",
                 "dateOfBirth":"1990-01-01","phone":"0781999888",
                 "email":"%s","fullName":"Test Applicant"}
                """.formatted(email);

        JsonNode challenge = body(post("/api/v1/registration/personal/start"), start);

        String code = lastCodeSentTo(email);

        JsonNode verified =
                body(
                        post("/api/v1/registration/personal/verify"),
                        """
                        {"challengeId":"%s","code":"%s"}
                        """
                                .formatted(challenge.get("challengeId").asString(), code));

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
                                        post(
                                                        "/api/v1/admin/applications/{id}/create-account",
                                                        applicationId)
                                                .with(httpBasic(ADMIN, STAFF_PASSWORD))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(ONE_CURRENT_ACCOUNT))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        // No password yet — one does not exist until a manager approves.
        return created.get("customer").get("id").asString();
    }

    private void approve(String customerId) throws Exception {
        mvc.perform(
                        post("/api/v1/admin/customers/{id}/approve", customerId)
                                .with(httpBasic(MANAGER, STAFF_PASSWORD)))
                .andExpect(status().isOk());
    }

    /** Registers, creates, approves, and reads the password out of the approval email. */
    private NewAccount approvedAccount(String email) throws Exception {
        String customerId = createAccountFor(email);
        approve(customerId);
        return new NewAccount(customerId, email, emailedPassword(email));
    }

    /**
     * The temporary password, taken from the message the bank sent.
     *
     * <p>Deliberately the only way these tests can obtain it. If a future change put the
     * password back into an API response, every test here would still pass while the
     * property that matters — that it travels by email and nowhere else — had been lost.
     * Reading it from the outbox is what keeps them honest.
     */
    private String emailedPassword(String email) {
        var message =
                outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                        .filter(m -> m.kind() == OutboxKind.ACCOUNT_APPROVED)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no approval email was sent to " + email));

        var matcher =
                Pattern.compile("Temporary password: ([A-Z2-9-]+)").matcher(message.body());
        assertThat(matcher.find())
                .as("the approval email carries the temporary password")
                .isTrue();
        return matcher.group(1);
    }

    /** An ACTIVE account whose customer has already chosen their own password. */
    private NewAccount settledAccount(String email) throws Exception {
        NewAccount account = approvedAccount(email);

        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(email, account.temporaryPassword())))
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
                                                .formatted(
                                                        account.temporaryPassword(),
                                                        CHOSEN_PASSWORD)))
                .andExpect(status().isNoContent());

        return account;
    }

    /* ------------------------------------------------------------ helpers */

    private static String login(String identifier, String password) {
        return """
               {"identifier":"%s","password":"%s"}
               """.formatted(identifier, password);
    }

    private JsonNode body(MockHttpServletRequestBuilder request, String content) throws Exception {
        return json.readTree(
                mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(content))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    /**
     * Reads the code out of the outbox, which is the only place it exists.
     *
     * <p>Nothing logs it and no endpoint returns it, which is the point — so a test has to
     * go to the recorded message, exactly as a member of staff would.
     */
    private String lastCodeSentTo(String email) {
        var message = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).get(0);
        var matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(message.body());
        assertThat(matcher.find())
                .as("a six-digit code in the message sent to %s", email)
                .isTrue();
        return matcher.group(1);
    }

    /* --------------------------------------------------- before approval */

    @Test
    @DisplayName("before approval there is no password at all, so nothing opens the account")
    void pendingApprovalHasNoCredential() throws Exception {
        /*
         * This test used to sign in with the temporary password and assert a 423 "waiting
         * for approval". That was possible because the password was issued when the
         * administrator created the account. It is now issued at approval and emailed, so
         * between the two steps the account has no working credential whatsoever.
         *
         * The property is stronger than it was: an account awaiting approval cannot be
         * signed into because there is nothing to sign in WITH, rather than because a
         * status check refuses it. Nothing has been emailed either, so nobody holds a
         * password that a later approval would quietly bring to life.
         */
        String email = "pending@example.rw";
        createAccountFor(email);

        assertThat(
                        outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                                .noneMatch(m -> m.body().contains("Temporary password:")))
                .as("no password is emailed before a manager approves")
                .isTrue();

        // Nothing opens it, and the refusal gives nothing away.
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(email, "ANY-THING-AT-ALL-1")))
                .andExpect(status().isUnauthorized());

        assertThat(signIns.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the password arrives by email, and only at approval")
    void passwordArrivesOnlyAtApproval() throws Exception {
        String email = "onapproval@example.rw";
        String customerId = createAccountFor(email);

        approve(customerId);

        String password = emailedPassword(email);
        assertThat(password)
                .as("the emailed password has the issued shape")
                .matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");

        // And it works.
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    @DisplayName("an emailed password stops working once it expires")
    void temporaryPasswordExpires() throws Exception {
        /*
         * The control that bounds the cost of sending a credential by email. Without it a
         * mailbox breached a year later still yields a working bank login.
         *
         * The clock is moved rather than waited on: the expiry is pushed into the past
         * directly, which is the only way to test three days of elapsed time.
         */
        NewAccount account = approvedAccount("expired@example.rw");

        var customer = customers.findAll().get(0);
        assertThat(customer.temporaryPasswordExpiresAt())
                .as("an emailed password must carry an expiry")
                .isNotNull();

        customer.issueTemporaryPassword(
                customer.passwordHash(), java.time.Instant.now().minusSeconds(60));
        customers.save(customer);

        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("expired")));
    }

    @Test
    @DisplayName("a password the customer chose never expires")
    void chosenPasswordDoesNotExpire() throws Exception {
        /*
         * The expiry must not leak past the temporary password. Locking a customer out of
         * a password they chose, because of a timestamp attached to one the bank sent,
         * would be a self-inflicted outage.
         */
        NewAccount account = settledAccount("nonexpiring@example.rw");

        var customer = customers.findAll().get(0);
        assertThat(customer.mustChangePassword()).isFalse();
        assertThat(customer.temporaryPasswordExpiresAt())
                .as("replacing the password clears the expiry")
                .isNull();

        outbox.deleteAll();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), CHOSEN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CHALLENGE_REQUIRED"));
    }

    @Test
    @DisplayName("a wrong password and an unknown customer are indistinguishable")
    void noExistenceOracle() throws Exception {
        NewAccount account = approvedAccount("real@example.rw");

        String wrongPassword =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(login(account.email(), "Definitely-Not-It-1")))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String unknownCustomer =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                login(
                                                        "nobody@example.rw",
                                                        "Definitely-Not-It-1")))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        /*
         * Compared on the message, not the whole body: the correlation reference differs
         * per request by design. If these two ever diverge, the sign-in form has become a
         * way to ask the bank who its customers are.
         */
        assertThat(field(unknownCustomer, "message")).isEqualTo(field(wrongPassword, "message"));
    }

    private String field(String responseBody, String name) {
        return json.readTree(responseBody).get(name).asString();
    }

    /* ------------------------------------------------- temporary password */

    @Test
    @DisplayName("the temporary password opens a session that must change it")
    void temporaryPasswordAsksForAChange() throws Exception {
        NewAccount account = approvedAccount("temp@example.rw");

        MockHttpSession session = new MockHttpSession();

        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"))
                /*
                 * No one-time code for a temporary password. A second factor guarding a
                 * credential that is about to be discarded protects nothing, and it lands
                 * at the customer's very first contact with the service.
                 *
                 * Absent, not null: default-property-inclusion is non_null, so the field is
                 * omitted. That matches the frontend's `challenge?: AuthChallenge`, which
                 * under exactOptionalPropertyTypes is a different type from `| null`.
                 */
                .andExpect(jsonPath("$.challenge").doesNotExist());

        mvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                /*
                 * Empty, not merely flagged. Every permission check the frontend makes
                 * fails on its own rather than each screen having to notice the flag.
                 */
                .andExpect(jsonPath("$.user.permissions").isEmpty());
    }

    @Test
    @DisplayName("and that session is refused everywhere else")
    void temporaryPasswordSessionIsRefusedElsewhere() throws Exception {
        NewAccount account = approvedAccount("gated@example.rw");

        MockHttpSession gated = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(gated)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        /*
         * An unmapped path stands in for every banking endpoint not yet written, and that
         * is deliberate: authorisation runs before handler mapping, so the status tells us
         * which rule the request met rather than whether a controller happens to exist.
         *
         * 403 here means the catch-all refused the MUST_CHANGE_PASSWORD authority. The
         * companion assertion in fullSessionReachesTheCatchAll() gets 404 on the same path
         * with a settled session, which is what proves the difference is the authority and
         * not the routing.
         *
         * This caught a real bug. The catch-all was authenticated(), and a
         * MUST_CHANGE_PASSWORD session is authenticated — so it passed. Nothing was
         * exposed, because no banking endpoint exists yet, but the first one to land would
         * have been reachable with a password bank staff had just read aloud.
         */
        mvc.perform(get("/api/v1/cards").session(gated)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/transfers").session(gated)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("changing the temporary password opens the portal, on a new session id")
    void changingTheTemporaryPasswordOpensThePortal() throws Exception {
        NewAccount account = approvedAccount("change@example.rw");

        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        HttpSession after =
                mvc.perform(
                                post("/api/v1/auth/password/change-temporary")
                                        .session(session)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"currentPassword":"%s","newPassword":"%s"}
                                                """
                                                        .formatted(
                                                                account.temporaryPassword(),
                                                                CHOSEN_PASSWORD)))
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        /*
         * The old session is gone. Its privileges just changed, and an id that was valid
         * under the lesser state must not be valid under the greater one.
         */
        assertThat(session.isInvalid()).isTrue();

        mvc.perform(get("/api/v1/session").session((MockHttpSession) after))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.user.permissions").isNotEmpty())
                .andExpect(jsonPath("$.user.permissions[0]").value("RETAIL_ACCOUNT_VIEW"));

        assertThat(customers.findAll().get(0).mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("the temporary password stops working once it is replaced")
    void temporaryPasswordIsSpent() throws Exception {
        NewAccount account = settledAccount("spent@example.rw");

        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a weak new password is refused, and says everything that is wrong at once")
    void weakPasswordRefused() throws Exception {
        NewAccount account = approvedAccount("weak@example.rw");

        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        mvc.perform(
                        post("/api/v1/auth/password/change-temporary")
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s","newPassword":"kigali"}
                                        """
                                                .formatted(account.temporaryPassword())))
                .andExpect(status().isUnprocessableContent())
                // All three faults in one message, so nobody has to guess the rules one
                // submit at a time.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("12 characters"),
                                org.hamcrest.Matchers.containsString("capital letter"),
                                org.hamcrest.Matchers.containsString("a number"))));

        // And the account is untouched.
        assertThat(customers.findAll().get(0).mustChangePassword()).isTrue();
    }

    /* ------------------------------------------------------- the code step */

    @Test
    @DisplayName("a settled password gets a code, not a session")
    void passwordAloneIsNotEnough() throws Exception {
        NewAccount account = settledAccount("code@example.rw");
        outbox.deleteAll();

        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), CHOSEN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CHALLENGE_REQUIRED"))
                .andExpect(jsonPath("$.challenge.challengeId").isNotEmpty())
                // Masked: the hint is there to remind the customer which inbox to open,
                // not to confirm the address to whoever has their password.
                .andExpect(jsonPath("$.challenge.deliveryHint").value("c***@example.rw"));

        /*
         * The property the whole three-step shape exists for: the password produced no
         * session. If this ever passes, a stolen password is a full sign-in.
         */
        mvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the code completes the sign-in")
    void codeCompletesSignIn() throws Exception {
        NewAccount account = settledAccount("full@example.rw");
        outbox.deleteAll();

        MockHttpSession session = new MockHttpSession();
        JsonNode challenge =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/auth/login")
                                                .session(session)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(login(account.email(), CHOSEN_PASSWORD)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        String challengeId = challenge.get("challenge").get("challengeId").asString();

        mvc.perform(
                        post("/api/v1/auth/verify")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"challengeId":"%s","code":"%s"}
                                        """
                                                .formatted(
                                                        challengeId,
                                                        lastCodeSentTo(account.email()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETE"));

        mvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.user.userType").value("RETAIL"))
                /*
                 * Masked. The full customer number is how the bank finds an account, so it
                 * does not belong in a payload the browser holds and every screen renders.
                 */
                .andExpect(jsonPath("$.user.customerNumber").value(
                        org.hamcrest.Matchers.startsWith("****")))
                .andExpect(jsonPath("$.user.customerNumber").value(
                        org.hamcrest.Matchers.hasLength(8)));
    }

    @Test
    @DisplayName("a used code cannot be used twice")
    void codeIsSingleUse() throws Exception {
        NewAccount account = settledAccount("replay@example.rw");
        outbox.deleteAll();

        JsonNode challenge =
                body(post("/api/v1/auth/login"), login(account.email(), CHOSEN_PASSWORD));
        String challengeId = challenge.get("challenge").get("challengeId").asString();
        String code = lastCodeSentTo(account.email());

        String verify =
                """
                {"challengeId":"%s","code":"%s"}
                """.formatted(challengeId, code);

        mvc.perform(
                        post("/api/v1/auth/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verify))
                .andExpect(status().isOk());

        mvc.perform(
                        post("/api/v1/auth/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(verify))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("wrong codes run out of attempts, and the count actually decreases")
    void attemptsAreCounted() throws Exception {
        NewAccount account = settledAccount("brute@example.rw");
        outbox.deleteAll();

        JsonNode challenge =
                body(post("/api/v1/auth/login"), login(account.email(), CHOSEN_PASSWORD));
        String challengeId = challenge.get("challenge").get("challengeId").asString();

        /*
         * The assertion is on the sequence, not just on the eventual refusal.
         *
         * The registration flow had this exact bug: the counter was incremented inside the
         * transaction that then threw, so every wrong code rolled the increment back and
         * reported the same number of attempts left forever — leaving a six-digit code
         * walkable. A test that only checked "eventually refused" passed throughout.
         */
        String previous = null;
        boolean refused = false;

        for (int attempt = 1; attempt <= 8; attempt++) {
            var response =
                    mvc.perform(
                                    post("/api/v1/auth/verify")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"challengeId":"%s","code":"000000"}
                                                    """
                                                            .formatted(challengeId)))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            String message = field(response, "message");

            if (message.contains("no attempts left") || message.contains("has expired")) {
                refused = true;
                break;
            }

            assertThat(message)
                    .as("attempt %d must report a different remaining count than attempt %d",
                            attempt, attempt - 1)
                    .isNotEqualTo(previous);
            previous = message;
        }

        assertThat(refused).as("the challenge must eventually refuse further attempts").isTrue();

        // And the correct code is now worthless.
        mvc.perform(
                        post("/api/v1/auth/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"challengeId":"%s","code":"%s"}
                                        """
                                                .formatted(
                                                        challengeId,
                                                        lastCodeSentTo(account.email()))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("the sign-in code is never stored in a readable form")
    void codeIsHashed() throws Exception {
        NewAccount account = settledAccount("hashed@example.rw");
        outbox.deleteAll();

        body(post("/api/v1/auth/login"), login(account.email(), CHOSEN_PASSWORD));
        String code = lastCodeSentTo(account.email());

        var stored = challenges.findAll().get(challenges.findAll().size() - 1);
        assertThat(stored.codeHash()).doesNotContain(code);
        assertThat(stored.codeHash()).hasSize(64);
    }

    /* ------------------------------------------------------------- session */

    @Test
    @DisplayName("a full session reaches the catch-all that refuses a temporary one")
    void fullSessionReachesTheCatchAll() throws Exception {
        MockHttpSession session = signedIn("catchall@example.rw");

        /*
         * 404, not 403: the path has no controller, but the session was authorised to ask.
         * Paired with temporaryPasswordSessionIsRefusedElsewhere(), which gets 403 on this
         * same path, so the pair isolates authorisation from routing.
         *
         * THE PATH MOVED FROM /accounts TO /cards, and the reason is worth recording: this
         * pair needs a path with NO controller, and /accounts grew one when customers
         * began seeing the accounts an administrator enters for them. If /cards is ever
         * implemented, move this pair again rather than changing the expectation — the
         * whole value of these two tests is the contrast between 403 and 404 on one path.
         */
        mvc.perform(get("/api/v1/cards").session(session)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("logging out ends the session on the server, not just in the browser")
    void logoutInvalidatesServerSide() throws Exception {
        MockHttpSession session = signedIn("bye@example.rw");

        mvc.perform(get("/api/v1/session").session(session)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        /*
         * The same cookie again. "Log out" on a shared machine has to mean the server
         * stops honouring the session, whatever the browser was told.
         */
        mvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an anonymous caller cannot read a session")
    void anonymousHasNoSession() throws Exception {
        mvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the password change needs a CSRF token")
    void csrfIsEnforced() throws Exception {
        NewAccount account = approvedAccount("csrf@example.rw");

        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        /*
         * No .with(csrf()). The frontend sends cookies with credentials: 'include', so
         * without this any page on the internet could make the customer's browser issue
         * an authenticated write to this API.
         */
        mvc.perform(
                        post("/api/v1/auth/password/change-temporary")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s","newPassword":"%s"}
                                        """
                                                .formatted(
                                                        account.temporaryPassword(),
                                                        CHOSEN_PASSWORD)))
                .andExpect(status().isForbidden());

        assertThat(customers.findAll().get(0).mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("sign-in is recorded, so the customer can be shown the previous one")
    void signInIsRecorded() throws Exception {
        String email = "history@example.rw";
        NewAccount account = approvedAccount(email);

        /* ---- the genuine first sign-in: nothing before it ---- */
        MockHttpSession first = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(first)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(email, account.temporaryPassword())))
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        /*
         * Absent, rather than null or an empty string, because there is no previous
         * sign-in to report.
         *
         * This is the contract the dashboard header got wrong: it rendered "Last sign-in
         * {value} from {value}" unconditionally, so a brand-new customer read "Last
         * sign-in — from undefined". Every customer of a bank that has just started
         * onboarding is a brand-new customer, so this was the common case, not the edge.
         */
        mvc.perform(get("/api/v1/session").session(first))
                .andExpect(jsonPath("$.user.lastLoginAt").doesNotExist())
                .andExpect(jsonPath("$.user.lastLoginLocation").doesNotExist());

        /* ---- settle the password, then sign in properly ---- */
        mvc.perform(
                        post("/api/v1/auth/password/change-temporary")
                                .session(first)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s","newPassword":"%s"}
                                        """
                                                .formatted(
                                                        account.temporaryPassword(),
                                                        CHOSEN_PASSWORD)))
                .andExpect(status().isNoContent());

        outbox.deleteAll();
        MockHttpSession second = signedInAgain(email);

        /*
         * And now there IS one to report — the temporary-password sign-in.
         *
         * That one used to be skipped, because it takes no one-time code, which left the
         * sign-in a customer most needs to see missing from their own history: the
         * temporary password was read off a screen by a member of staff and handed over,
         * so if somebody else used it first, this line is the only place that would show
         * it.
         */
        /*
         * THIS ASSERTION USED TO READ `lastLoginLocation == "Kigali, Rwanda"`, and it
         * passed, and it was wrong — it locked in a string that all four sign-in call
         * sites passed as a literal, with no geo-IP lookup anywhere in the service. A test
         * asserting a fabricated value is worse than no test: it defends the fabrication.
         * See SignInMethod and V19.
         *
         * What is asserted instead is the method, which the service genuinely knows. The
         * device is deliberately NOT asserted non-null: MockMvc sends no User-Agent, so
         * absent is the correct answer here and the response must survive it.
         */
        mvc.perform(get("/api/v1/session").session(second))
                .andExpect(jsonPath("$.user.lastLoginAt").isNotEmpty())
                .andExpect(jsonPath("$.user.lastLoginMethod").value("The temporary password"))
                .andExpect(jsonPath("$.user.lastLoginDevice").doesNotExist());

        assertThat(signIns.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("the temporary-password sign-in is recorded too")
    void temporaryPasswordSignInIsRecorded() throws Exception {
        NewAccount account = approvedAccount("recorded@example.rw");

        assertThat(signIns.findAll()).isEmpty();

        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), account.temporaryPassword())))
                .andExpect(jsonPath("$.outcome").value("PASSWORD_CHANGE_REQUIRED"));

        assertThat(signIns.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a refused sign-in is not recorded as one")
    void failedSignInIsNotRecorded() throws Exception {
        NewAccount account = approvedAccount("refused@example.rw");

        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login(account.email(), "Wrong-Password-1")))
                .andExpect(status().isUnauthorized());

        /*
         * The history has to mean "somebody got in". A failed attempt in the same list
         * would make the control useless in both directions: alarming when nothing
         * happened, and unremarkable when something did.
         */
        assertThat(signIns.findAll()).isEmpty();
    }

    @Test
    @DisplayName("nothing in the sign-in emails or responses carries a password")
    void noCredentialTravels() throws Exception {
        NewAccount account = settledAccount("leak@example.rw");
        outbox.deleteAll();

        String loginResponse =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(login(account.email(), CHOSEN_PASSWORD)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(loginResponse).doesNotContain(CHOSEN_PASSWORD);
        assertThat(loginResponse).doesNotContain(account.temporaryPassword());

        var codeMail =
                outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(account.email()).stream()
                        .filter(m -> m.kind() == OutboxKind.EMAIL_VERIFICATION)
                        .findFirst()
                        .orElseThrow();

        assertThat(codeMail.body()).doesNotContain(CHOSEN_PASSWORD);
        // And it tells the customer what to do if the code was not their doing.
        assertThat(codeMail.body()).contains("somebody may have your password");
        assertThat(codeMail.body()).contains("never ask you for this code");
    }

    /* ------------------------------------------------- signed-in fixtures */

    /** Registers, approves, settles the password and completes a code sign-in. */
    private MockHttpSession signedIn(String email) throws Exception {
        settledAccount(email);
        outbox.deleteAll();
        return signedInAgain(email);
    }

    /** A further code sign-in for an account that has already settled its password. */
    private MockHttpSession signedInAgain(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();

        JsonNode challenge =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/auth/login")
                                                .session(session)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(login(email, CHOSEN_PASSWORD)))
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
                                                        lastCodeSentTo(email))))
                .andExpect(status().isOk());

        return session;
    }
}
