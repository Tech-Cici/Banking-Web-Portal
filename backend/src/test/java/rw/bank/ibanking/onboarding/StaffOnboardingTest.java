package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.bank.ibanking.onboarding.domain.CustomerStatus;
import rw.bank.ibanking.onboarding.domain.OutboxKind;
import tools.jackson.databind.JsonNode;

/**
 * The bank side: an administrator creates, a manager releases, and the customer is emailed.
 *
 * <p>Everything here goes through the real filter chain with real credentials, because the
 * properties worth testing are authorisation properties. A service-level test would prove
 * the methods work and prove nothing about whether a manager can call the admin's endpoint.
 */
class StaffOnboardingTest extends OnboardingIntegrationTest {

    private static final String ADMIN = "admin@zigama.local";
    private static final String MANAGER = "manager@zigama.local";
    private static final String PASSWORD = "ZigamaStaff1";

    /* ----------------------------------------------------------- helpers */

    private MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
        return request.with(httpBasic(user, PASSWORD));
    }
    private JsonNode createAccount(String applicationId) throws Exception {
        return json.readTree(
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
    }

    /**
     * The temporary password, out of the approval email — the only place it exists.
     *
     * <p>No endpoint returns it. Reading it here rather than from a response is what
     * keeps these tests honest: if a future change put it back into an API payload, a
     * test that took it from there would still pass while the property had been lost.
     */
    private String emailedPassword(String email) {
        var message =
                outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                        .filter(m -> m.kind() == OutboxKind.ACCOUNT_APPROVED)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no approval email for " + email));

        var matcher =
                java.util.regex.Pattern.compile("Temporary password: ([A-Z2-9-]+)")
                        .matcher(message.body());
        assertThat(matcher.find()).as("the approval email carries the password").isTrue();
        return matcher.group(1);
    }

    /* -------------------------------------------------------------- tests */

    @Test
    @DisplayName("the whole chain: register, create, approve, customer emailed")
    void endToEnd() throws Exception {
        String email = "chain@example.rw";
        String applicationId = registerApplicant(email);

        JsonNode created = createAccount(applicationId);
        String customerId = created.get("customer").get("id").asString();

        /*
         * No password in the create response any more. It is issued at approval and
         * emailed, so there is nothing here for an administrator to see or hand over.
         */
        assertThat(created.get("temporaryPassword"))
                .as("no endpoint returns a credential")
                .isNull();
        assertThat(created.get("customer").get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(created.get("customer").get("mustChangePassword").asBoolean()).isTrue();

        // The manager's step. THIS is what issues and sends the password.
        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.approvedByName").value("Immaculee Mukandayisenga"));

        var approvalMail =
                outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                        .filter(m -> m.kind() == OutboxKind.ACCOUNT_APPROVED)
                        .findFirst()
                        .orElseThrow();

        assertThat(approvalMail.subject()).contains("ready");
        assertThat(approvalMail.body()).contains("approved");

        /*
         * The bank decided to email the temporary password. This test asserted the exact
         * opposite until that decision, and the inversion is the point: the email now
         * carries the credential, so the three things that bound the cost of that must
         * all be present in the message the customer actually receives.
         */
        String temporary = emailedPassword(email);
        assertThat(temporary).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");

        assertThat(approvalMail.body())
                .as("told to replace it immediately")
                .contains("replace the password straight away");

        /*
         * IT NAMES THE ADDRESS TO SIGN IN WITH, which it did not.
         *
         * The message printed the customer number directly above the temporary password
         * and said only "sign in with the temporary password above", so the obvious pair
         * to type was the number and the password. Sign-in looks a customer up by EMAIL
         * and has no other lookup, so that pair fails with the same generic message a
         * wrong password produces — on a new customer's first contact with the service,
         * holding a password that was in fact correct.
         */
        assertThat(approvalMail.body())
                .as("names the email address as the thing to sign in with")
                .contains("Sign in with your email address:");
        assertThat(approvalMail.body())
                .as("and prints that address")
                .contains(email);
        assertThat(approvalMail.body())
                .as("and says the customer number is NOT what to sign in with")
                .contains("it is not what you sign in with");
        assertThat(approvalMail.body())
                .as("told when it stops working")
                .contains("stops working in");
        assertThat(approvalMail.body())
                .as("told to delete the message afterwards")
                .contains("Delete this email");

        /*
         * The old promise is gone rather than softened. Leaving "we will never email your
         * password" in a message that contains the password would teach customers to
         * ignore the assurance that protects them from a real phishing attempt.
         */
        assertThat(approvalMail.body()).doesNotContain("did not include your password");
        assertThat(approvalMail.body()).doesNotContain("never will");
    }

    @Test
    @DisplayName("the temporary password is never stored in a readable form")
    void passwordIsHashed() throws Exception {
        String email = "hash@example.rw";
        String applicationId = registerApplicant(email);
        String customerId = createAccount(applicationId).get("customer").get("id").asString();
        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk());
        String temporary = emailedPassword(email);

        var customer = customers.findAll().get(0);
        assertThat(customer.passwordHash()).doesNotContain(temporary);
        assertThat(customer.passwordHash()).startsWith("$2");
    }

    @Test
    @DisplayName("and there is no endpoint that can read it back")
    void noEndpointReturnsThePassword() throws Exception {
        String email = "readback@example.rw";
        String applicationId = registerApplicant(email);
        String customerId = createAccount(applicationId).get("customer").get("id").asString();
        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk());
        String temporary = emailedPassword(email);

        String listing =
                mvc.perform(as(ADMIN, get("/api/v1/admin/customers")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(listing).doesNotContain(temporary);
        assertThat(listing).doesNotContain("passwordHash");
    }

    /* ------------------------------------------------------- authorisation */

    @Test
    @DisplayName("a manager cannot create an account")
    void managerCannotCreate() throws Exception {
        String applicationId = registerApplicant("wrongrole@example.rw");

        mvc.perform(
                        as(
                                MANAGER,
                                post(
                                        "/api/v1/admin/applications/{id}/create-account",
                                        applicationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ONE_CURRENT_ACCOUNT)))
                .andExpect(status().isForbidden());

        assertThat(customers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an admin cannot approve an account")
    void adminCannotApprove() throws Exception {
        String applicationId = registerApplicant("adminapprove@example.rw");
        String customerId = createAccount(applicationId).get("customer").get("id").asString();

        mvc.perform(as(ADMIN, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isForbidden());

        assertThat(customers.findAll().get(0).status()).isEqualTo(CustomerStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("the staff endpoints are closed to anonymous callers")
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/v1/admin/applications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/customers")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong staff password is refused")
    void wrongPasswordRefused() throws Exception {
        mvc.perform(
                        get("/api/v1/admin/applications")
                                .with(httpBasic(ADMIN, "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------------- four eyes */

    @Test
    @DisplayName("approving twice is refused")
    void doubleApprovalRefused() throws Exception {
        String applicationId = registerApplicant("twice@example.rw");
        String customerId = createAccount(applicationId).get("customer").get("id").asString();

        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk());

        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already approved or rejected")));

        // And the customer was emailed once, not twice.
        assertThat(
                        outbox.findAll().stream()
                                .filter(m -> m.kind() == OutboxKind.ACCOUNT_APPROVED)
                                .count())
                .isEqualTo(1);
    }

    /* ---------------------------------------------------------- rejection */

    @Test
    @DisplayName("a rejection needs a reason, and the reason reaches the applicant")
    void rejectionNeedsAReason() throws Exception {
        String email = "rejected@example.rw";
        String applicationId = registerApplicant(email);
        String customerId = createAccount(applicationId).get("customer").get("id").asString();

        // No reason: refused before anything changes.
        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/reject", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"reason":"  "}
                                         """))
                .andExpect(status().isBadRequest());

        assertThat(customers.findAll().get(0).status()).isEqualTo(CustomerStatus.PENDING_APPROVAL);

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/reject", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"reason":"The national ID did not match our records."}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        var mail =
                outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).stream()
                        .filter(m -> m.kind() == OutboxKind.ACCOUNT_REJECTED)
                        .findFirst()
                        .orElseThrow();
        assertThat(mail.body()).contains("The national ID did not match our records.");
    }

    @Test
    @DisplayName("rejecting destroys the temporary password")
    void rejectionBurnsThePassword() throws Exception {
        String applicationId = registerApplicant("burn@example.rw");
        JsonNode created = createAccount(applicationId);
        String customerId = created.get("customer").get("id").asString();
        String hashBefore = customers.findAll().get(0).passwordHash();

        mvc.perform(
                        as(MANAGER, post("/api/v1/admin/customers/{id}/reject", customerId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"reason":"Details could not be verified."}
                                         """))
                .andExpect(status().isOk());

        // A member of staff had seen the old one, so it must no longer work.
        assertThat(customers.findAll().get(0).passwordHash()).isNotEqualTo(hashBefore);
    }

    /* ------------------------------------------------ what the admin sees */

    @Test
    @DisplayName("the customer payload carries every field the portal reads")
    void customerPayloadIsComplete() throws Exception {
        /*
         * A contract test, written after the portal crashed twice on fields the API did
         * not send — the second time on `accountMasks.join(...)`, immediately after an
         * administrator pressed "create account". TypeScript cannot catch that: the
         * frontend type describes what the API is BELIEVED to return.
         *
         * So the list below is the portal's Customer type, field for field. If a field is
         * added there, this fails until the API sends it — which is the only place the
         * mismatch can be caught before a member of staff meets it.
         */
        String applicationId = registerApplicant("payload@example.rw");
        JsonNode created = createAccount(applicationId);
        JsonNode customer = created.get("customer");

        for (String field :
                new String[] {
                    "id", "applicationId", "fullName", "email", "phone", "customerNumber",
                    "userType", "status", "mustChangePassword", "createdAt", "createdByName",
                    "accountMasks"
                }) {
            assertThat(customer.get(field))
                    .as("the portal reads customer.%s", field)
                    .isNotNull();
        }

        // accountMasks must be a LIST, because the portal calls .join() on it.
        assertThat(customer.get("accountMasks").isArray())
                .as("accountMasks must be an array, not absent and not a string")
                .isTrue();

        assertThat(customer.get("userType").asString()).isEqualTo("RETAIL");
        assertThat(customer.get("phone").asString()).isNotBlank();
        assertThat(customer.get("applicationId").asString()).isEqualTo(applicationId);

        /*
         * approvedAt and approvedByName are absent until a manager approves, which is
         * correct: the portal's type marks them optional. Present-and-null would be a
         * different type under exactOptionalPropertyTypes.
         */
        assertThat(customer.get("approvedByName")).isNull();

        String customerId = customer.get("id").asString();
        JsonNode approved =
                json.readTree(
                        mvc.perform(
                                        as(
                                                MANAGER,
                                                post(
                                                        "/api/v1/admin/customers/{id}/approve",
                                                        customerId)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        assertThat(approved.get("approvedByName").asString())
                .isEqualTo("Immaculee Mukandayisenga");
        assertThat(approved.get("approvedAt")).isNotNull();
    }

    /* ------------------------------------------------------ the overview */

    @Test
    @DisplayName("the overview counts move as the flow progresses")
    void summaryCounts() throws Exception {
        mvc.perform(as(ADMIN, get("/api/v1/admin/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(0))
                .andExpect(jsonPath("$.awaitingApproval").value(0))
                .andExpect(jsonPath("$.active").value(0))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.awaitingFirstSignIn").value(0));

        String applicationId = registerApplicant("counts@example.rw");

        mvc.perform(as(ADMIN, get("/api/v1/admin/summary")))
                .andExpect(jsonPath("$.submitted").value(1))
                .andExpect(jsonPath("$.awaitingApproval").value(0));

        String customerId = createAccount(applicationId).get("customer").get("id").asString();

        mvc.perform(as(ADMIN, get("/api/v1/admin/summary")))
                .andExpect(jsonPath("$.awaitingApproval").value(1))
                .andExpect(jsonPath("$.active").value(0));

        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk());

        mvc.perform(as(ADMIN, get("/api/v1/admin/summary")))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.awaitingApproval").value(0))
                /*
                 * The figure that matters most on that screen: approved, and the
                 * temporary password still unused. Each one is a credential a member of
                 * staff has seen, sitting unclaimed.
                 */
                .andExpect(jsonPath("$.awaitingFirstSignIn").value(1));
    }

    @Test
    @DisplayName("a manager may read the overview too")
    void summaryIsReadableByBoth() throws Exception {
        mvc.perform(as(MANAGER, get("/api/v1/admin/summary"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/summary")).andExpect(status().isUnauthorized());
    }

    /* ---------------------------------------------------- development reset */

    @Test
    @DisplayName("the development reset empties everything, and only an admin may call it")
    void devReset() throws Exception {
        String applicationId = registerApplicant("wipe@example.rw");
        createAccount(applicationId);

        assertThat(customers.findAll()).isNotEmpty();
        assertThat(applications.findAll()).isNotEmpty();

        // A manager is not an administrator, even here.
        mvc.perform(as(MANAGER, post("/api/v1/admin/dev/reset")))
                .andExpect(status().isForbidden());
        assertThat(customers.findAll()).isNotEmpty();

        mvc.perform(get("/api/v1/admin/dev/reset")).andExpect(status().isUnauthorized());

        mvc.perform(as(ADMIN, post("/api/v1/admin/dev/reset")))
                .andExpect(status().isNoContent());

        assertThat(customers.findAll()).isEmpty();
        assertThat(applications.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
        assertThat(verifications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the application carries the name the applicant gave")
    void nameComesFromTheApplicant() throws Exception {
        /*
         * This replaces a test that asserted the opposite.
         *
         * For a while the service filled the name in from a placeholder "bank record",
         * on the reasoning that a name an applicant types is a name they could type
         * somebody else's into. That reasoning only holds if the bank can supply the
         * name, and here it cannot: matching these details against the bank's records
         * happens outside this system, and the manager's approval is where it is
         * enforced. So the applicant is the source and staff verify the claim.
         *
         * What still has to be true is that the name is the SUBMITTED one — not a
         * fallback, and above all not the email address, which is what an earlier version
         * of this code recorded when the field was absent.
         */
        String applicationId = registerApplicant("claimedname@example.rw");

        mvc.perform(as(ADMIN, get("/api/v1/admin/applications/{id}", applicationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Test Applicant"))
                .andExpect(jsonPath("$.displayName").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("@"))));
    }

    @Test
    @DisplayName("any well-formed account number is accepted, because matching happens elsewhere")
    void anyWellFormedAccountNumberIsAccepted() throws Exception {
        /*
         * The service used to accept exactly one hardcoded account number and refuse
         * everything else, which made it impossible for a real client to register. It is
         * not this system's job to decide whether an account exists — that is answered
         * against the bank's records by a separate process, and the manager's approval is
         * what records that it was done.
         */
        for (String accountNumber : new String[] {"1234567890", "9876543210", "4000123456789012"}) {
            String email = "acct" + accountNumber + "@example.rw";
            mvc.perform(
                            post("/api/v1/registration/personal/start")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"accountNumber":"%s","nationalId":"1199570099999999",
                                             "dateOfBirth":"1990-01-01","phone":"0781999888",
                                             "email":"%s","fullName":"Test Applicant"}
                                            """
                                                    .formatted(accountNumber, email)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the format is still enforced, and so is the name")
    void formatIsStillEnforced() throws Exception {
        /*
         * Accepting any account number is not the same as accepting anything. The shape
         * is the one thing this service can judge, so it still does — a number that
         * cannot be an account number is a typo, and telling somebody at the form is
         * kinder than sending them to a branch.
         */
        record Bad(String field, String accountNumber, String fullName) {}

        var cases =
                new Bad[] {
                    new Bad("accountNumber", "12345", "Test Applicant"), // too short
                    new Bad("accountNumber", "12345678901234567", "Test Applicant"), // too long
                    new Bad("accountNumber", "12345abcde", "Test Applicant"), // not digits
                    new Bad("fullName", "1234567890", "   "), // no name
                };

        for (Bad bad : cases) {
            mvc.perform(
                            post("/api/v1/registration/personal/start")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"accountNumber":"%s","nationalId":"1199570099999999",
                                             "dateOfBirth":"1990-01-01","phone":"0781999888",
                                             "email":"bad@example.rw","fullName":"%s"}
                                            """
                                                    .formatted(bad.accountNumber(), bad.fullName())))
                    .andExpect(status().isBadRequest());
        }

        // And nothing was created by any of them.
        assertThat(applications.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the admin sees that the applicant confirmed their email")
    void emailVerifiedIsVisible() throws Exception {
        String applicationId = registerApplicant("verified@example.rw");

        mvc.perform(as(ADMIN, get("/api/v1/admin/applications/{id}", applicationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }
}
