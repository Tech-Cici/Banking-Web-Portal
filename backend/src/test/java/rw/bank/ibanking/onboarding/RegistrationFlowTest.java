package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import rw.bank.ibanking.onboarding.domain.ApplicationStatus;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import tools.jackson.databind.JsonNode;

/**
 * The registration flow, through the real filter chain, the real database and the real
 * mailer.
 *
 * <p>Sending is off in the test profile, so the mailer records rather than delivers — which
 * is what lets the test read the code out of the outbox. That is exactly how a developer
 * walks the flow without a mailbox, so the mechanism is under test too.
 *
 * <p>IT CLEARS THE DATABASE THROUGH THE SHARED BASE CLASS NOW, and that is the fix to a
 * real failure rather than tidying. It used to delete applications itself, in its own
 * order, without touching customers — and `customers` has a foreign key to `applications`.
 * So the moment any earlier test class left a customer behind, every test here failed in
 * its own setup with a referential integrity violation that named a constraint and said
 * nothing about the cause. Whether it broke depended entirely on which class Surefire ran
 * first, which is the worst property a test can have.
 *
 * <p>{@link OnboardingIntegrationTest#reset()} is the one place that order is written
 * down. The next table that references `customers` gets handled there, once.
 */
class RegistrationFlowTest extends OnboardingIntegrationTest {

    /** Any well-formed number works now; this one is just a readable constant. */
    private static final String KNOWN_ACCOUNT = "1234567890";

    private String startBody(String email, String account) {
        return """
               {"accountNumber":"%s","nationalId":"1199570099999999",
                "dateOfBirth":"1990-01-01","phone":"0781999888",
                "email":"%s","fullName":"Test Applicant"}
               """
                .formatted(account, email);
    }

    private JsonNode postJson(String path, String body) throws Exception {
        MvcResult result =
                mvc.perform(
                                post(path)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    /** Reads the code out of the recorded message, the way a developer would. */
    private String codeFromOutbox(String email) {
        var messages = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email);
        assertThat(messages).isNotEmpty();
        var message = messages.get(0);
        assertThat(message.kind()).isEqualTo(OutboxKind.EMAIL_VERIFICATION);

        var matcher = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(message.body());
        assertThat(matcher.find()).as("the message contains a 6-digit code").isTrue();
        return matcher.group(1);
    }

    @Test
    @DisplayName("register, confirm the email, and the application reaches the queue")
    void happyPath() throws Exception {
        String email = "applicant@example.rw";

        JsonNode challenge = postJson("/api/v1/registration/personal/start", startBody(email, KNOWN_ACCOUNT));
        assertThat(challenge.get("challengeId").asString()).isNotBlank();
        assertThat(challenge.get("otpLength").asInt()).isEqualTo(6);

        // The masked hint shows the domain but not the local part.
        assertThat(challenge.get("deliveryHint").asString()).isEqualTo("a********@example.rw");

        String code = codeFromOutbox(email);

        JsonNode verified =
                postJson(
                        "/api/v1/registration/personal/verify",
                        """
                        {"challengeId":"%s","code":"%s"}
                        """
                                .formatted(challenge.get("challengeId").asString(), code));
        String token = verified.get("verificationToken").asString();
        assertThat(token).isNotBlank();

        mvc.perform(
                        post("/api/v1/registration/personal/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"verificationToken":"%s"}
                                         """.formatted(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.reference").exists());

        var stored = applications.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).status()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(stored.get(0).email()).isEqualTo(email);
        // The whole point of the flow: it reaches the admin marked as confirmed.
        assertThat(stored.get(0).emailVerified()).isTrue();
    }

    @Test
    @DisplayName("the code is never stored in clear")
    void codeIsHashed() throws Exception {
        String email = "hashed@example.rw";
        postJson("/api/v1/registration/personal/start", startBody(email, KNOWN_ACCOUNT));
        String code = codeFromOutbox(email);

        var stored = verifications.findAll().get(0);
        assertThat(stored.codeHash()).isNotEqualTo(code);
        assertThat(stored.codeHash()).hasSize(64);
        assertThat(stored.codeHash()).doesNotContain(code);
    }

    @Test
    @DisplayName("a wrong code is refused and counted, and dies after five tries")
    void attemptsAreCounted() throws Exception {
        String email = "brute@example.rw";
        JsonNode challenge = postJson("/api/v1/registration/personal/start", startBody(email, KNOWN_ACCOUNT));
        String id = challenge.get("challengeId").asString();
        String realCode = codeFromOutbox(email);
        String wrongCode = realCode.equals("000000") ? "111111" : "000000";

        for (int i = 1; i <= 5; i++) {
            JsonNode error =
                    postJson(
                            "/api/v1/registration/personal/verify",
                            """
                            {"challengeId":"%s","code":"%s"}
                            """.formatted(id, wrongCode));
            assertThat(error.get("message").asString()).contains("not correct");
        }

        // Sixth try: the code is gone, even though it has not expired and is now correct.
        JsonNode dead =
                postJson(
                        "/api/v1/registration/personal/verify",
                        """
                        {"challengeId":"%s","code":"%s"}
                        """.formatted(id, realCode));
        assertThat(dead.get("message").asString()).contains("start again");
        assertThat(verifications.findAll().get(0).attempts()).isEqualTo((short) 5);
    }

    @Test
    @DisplayName("a verification token works once")
    void tokenIsSingleUse() throws Exception {
        String email = "once@example.rw";
        JsonNode challenge = postJson("/api/v1/registration/personal/start", startBody(email, KNOWN_ACCOUNT));
        String code = codeFromOutbox(email);
        JsonNode verified =
                postJson(
                        "/api/v1/registration/personal/verify",
                        """
                        {"challengeId":"%s","code":"%s"}
                        """.formatted(challenge.get("challengeId").asString(), code));
        String token = verified.get("verificationToken").asString();
        String body = """
                      {"verificationToken":"%s"}
                      """.formatted(token);

        mvc.perform(post("/api/v1/registration/personal/complete")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Replaying it must not create a second application for one verification.
        mvc.perform(post("/api/v1/registration/personal/complete")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent());

        assertThat(applications.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("asking for codes repeatedly is rate limited, with Retry-After")
    void rateLimited() throws Exception {
        String email = "flooder@example.rw";
        String body = startBody(email, KNOWN_ACCOUNT);

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/registration/personal/start")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/v1/registration/personal/start")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("an unrecognised account number is accepted, and reveals nothing either way")
    void accountNumbersAreNotProbeable() throws Exception {
        /*
         * This test used to assert the opposite: that an unknown account number was
         * refused with a deliberately vague message. That behaviour is gone, because the
         * service has no way to know which numbers are real — matching happens against
         * the bank's records outside this system, and the manager's approval enforces it.
         *
         * The security property it was protecting is still worth having, and is now
         * satisfied more completely rather than less. A refusal that depends on whether
         * an account exists IS an existence oracle, however vaguely it is worded; a
         * registration endpoint that treats every well-formed number identically cannot
         * be used to test account numbers at all, because there is nothing to compare.
         *
         * What stops it being abused instead: the applicant must read a code sent to the
         * address they gave, staff see the application before anything is created, and
         * the rate limit above caps how much mail one address can trigger.
         */
        mvc.perform(
                        post("/api/v1/registration/personal/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(startBody("nobody@example.rw", "9999999999")))
                .andExpect(status().isOk());

        // Identical treatment: the response says the same thing for a different number.
        String first =
                mvc.perform(
                                post("/api/v1/registration/personal/start")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(startBody("other@example.rw", "1234567890")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String second =
                mvc.perform(
                                post("/api/v1/registration/personal/start")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(startBody("third@example.rw", "5555555555")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        /*
         * Compared with the challenge id and delivery hint removed, since those differ
         * per request by design. Everything else must match, or the shape of the response
         * becomes the oracle the message used to be.
         */
        assertThat(shapeOf(first)).isEqualTo(shapeOf(second));

        // Nothing is created by starting: an application needs the code and the last step.
        assertThat(applications.findAll()).isEmpty();
    }

    /** The response with the per-request values stripped, so two can be compared. */
    private String shapeOf(String responseBody) {
        JsonNode node = json.readTree(responseBody);
        return node.propertyNames().stream()
                .sorted()
                .map(
                        name ->
                                name
                                        + "="
                                        + switch (name) {
                                            case "challengeId", "deliveryHint" -> "<varies>";
                                            default -> node.get(name).asString();
                                        })
                .reduce("", (a, b) -> a + ";" + b);
    }

    @Test
    @DisplayName("an unconfirmed email creates no application at all")
    void unverifiedEmailNeverReachesTheQueue() throws Exception {
        /*
         * The property a member of staff relies on when they read "confirmed by the
         * applicant" on the admin screen.
         *
         * Starting a registration composes a message and nothing else. Until the code
         * that was sent to that address comes back, there is no application, so there is
         * nothing for an administrator to see and nothing that could be labelled
         * confirmed. The flag on the record is hardcoded true precisely because reaching
         * the step that creates it is only possible with a correct code — this test is
         * what makes that reasoning safe to rely on rather than merely stated.
         */
        postJson("/api/v1/registration/personal/start", startBody("unconfirmed@example.rw", KNOWN_ACCOUNT));

        assertThat(outbox.findAll()).as("a code was sent").hasSize(1);
        assertThat(applications.findAll()).as("but nothing is in the queue").isEmpty();

        // A made-up token cannot stand in for having read the message.
        mvc.perform(
                        post("/api/v1/registration/personal/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"verificationToken":"not-a-real-token"}
                                         """))
                .andExpect(status().isUnprocessableContent());

        assertThat(applications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("emailVerified is true only once the code has actually been entered")
    void emailVerifiedIsEarned() throws Exception {
        String email = "earned@example.rw";
        JsonNode challenge =
                postJson("/api/v1/registration/personal/start", startBody(email, KNOWN_ACCOUNT));

        // Wrong code first: still nothing in the queue.
        mvc.perform(
                        post("/api/v1/registration/personal/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"challengeId":"%s","code":"000000"}
                                        """
                                                .formatted(
                                                        challenge.get("challengeId").asString())))
                .andExpect(status().isUnprocessableContent());
        assertThat(applications.findAll()).isEmpty();

        // The real code.
        JsonNode verified =
                postJson(
                        "/api/v1/registration/personal/verify",
                        """
                        {"challengeId":"%s","code":"%s"}
                        """
                                .formatted(
                                        challenge.get("challengeId").asString(),
                                        codeFromOutbox(email)));

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

        var application = applications.findAll().get(0);
        assertThat(application.emailVerified())
                .as("the flag means a code sent to that address came back")
                .isTrue();
        assertThat(application.email()).isEqualTo(email);
    }

    @Test
    @DisplayName("a malformed request is rejected with field errors, before any rule runs")
    void validationFirst() throws Exception {
        mvc.perform(
                        post("/api/v1/registration/personal/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"accountNumber":"","nationalId":"12",
                                         "dateOfBirth":"1990-01-01","phone":"abc",
                                         "email":"not-an-email","fullName":"X"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        assertThat(outbox.findAll()).isEmpty();
    }
}
