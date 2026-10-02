package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.bank.ibanking.onboarding.domain.CorporateRole;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.UserType;
import tools.jackson.databind.JsonNode;

/**
 * A COMPANY'S MONEY: who it belongs to, who can reach it, and who cannot.
 *
 * <p>WHAT THIS EXISTS TO PREVENT. A company could register, staff could read the
 * application, and there it stopped: creating a login was refused, because this service
 * had no corporate customer of any kind. Then a whole schema arrived — a company, a
 * membership, a company_id on the account — and a schema proves nothing. The rule that
 * matters is a runtime one, and it lives in one method: {@code AccountAccess.mayAct}.
 *
 * <p>So this walks the real thing. A company registers, an administrator creates the
 * login, a manager approves it, the contact signs in, and the accounts the administrator
 * typed are there. Then the half that is easy to leave untested: a second company, a
 * personal customer, and a revoked member each try the same accounts and get nothing.
 *
 * <p>ON BOTH ENDPOINTS, EVERY TIME. Every visibility test here also tries to MOVE money,
 * not only to read it, because those were two separate checks in two files until recently
 * and the dangerous direction of a disagreement between them is the one where the payment
 * is more permissive than the screen. A test that only reads would pass against a ledger
 * that let anybody withdraw.
 */
@DisplayName("Corporate accounts")
class CorporateAccountTest extends OnboardingIntegrationTest {

    private static final String CONTACT = "director@inyange.example.rw";
    private static final String COMPANY = "Inyange Industries Ltd";

    /** One RWF current account, opened with 900,000 in it. RWF has NO minor unit. */
    private static final String COMPANY_ACCOUNT =
            """
            {"accounts":[{"accountNumber":"4001234567891","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"900000"}]}""";

    /** A different number, for the second company. */
    private static final String OTHER_COMPANY_ACCOUNT =
            """
            {"accounts":[{"accountNumber":"4009876543210","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"5000"}]}""";

    private static final String PERSONAL_ACCOUNT =
            """
            {"accounts":[{"accountNumber":"4005555555555","accountType":"SAVINGS",\
            "currency":"RWF","openingBalance":"1000"}]}""";

    /* ----------------------------------------------------------- helpers */

    /**
     * Registers a company, creates its login, approves it and signs the contact in.
     *
     * <p>Every step is a real HTTP call with real credentials, for the reason the base
     * class already gives: each of these steps has been individually green at a point
     * when the thing the bank wanted did not work end to end.
     */
    private MockHttpSession companyWithAccounts(
            String companyName, String contactEmail, String accountsBody) throws Exception {

        String applicationId = registerBusiness(companyName, contactEmail);

        mvc.perform(createAccountRequest(applicationId, accountsBody))
                .andExpect(status().isOk());

        String customerId = customerIdOf(contactEmail).toString();

        return signInAndSettle(
                contactEmail, approveAndReadTemporaryPassword(customerId, contactEmail));
    }

    private UUID customerIdOf(String email) {
        return customers.findAll().stream()
                .filter(c -> c.email().equalsIgnoreCase(email))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private CustomerAccountEntity accountNumbered(String accountNumber) {
        return customerAccounts.findByAccountNumberAndRemovedAtIsNull(accountNumber).stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "No account was stored for " + accountNumber));
    }

    private MockHttpServletRequestBuilder deposit(
            UUID accountId, MockHttpSession session, String key) {
        return post("/api/v1/accounts/{id}/deposit", accountId.toString())
                .session(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":"1000","description":"Cash in"}""");
    }

    private MockHttpServletRequestBuilder withdraw(
            UUID accountId, MockHttpSession session, String key) {
        return post("/api/v1/accounts/{id}/withdraw", accountId.toString())
                .session(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":"1000","description":"Cash out"}""");
    }

    /* --------------------------------------------- the company is admitted */

    @Test
    @DisplayName("THE ACCOUNTS BELONG TO THE COMPANY, not to the contact who was given the login")
    void theAccountsBelongToTheCompany() throws Exception {
        companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);

        var company =
                companies.findAll().stream()
                        .filter(c -> c.name().equals(COMPANY))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Creating the login did not admit a company."));

        CustomerAccountEntity account = accountNumbered("4001234567891");

        /*
         * THE ASSERTION THAT MATTERS. Everything else here would also pass if the account
         * had been created as the contact's own personal holding — the contact would see
         * it, could move money on it, and nothing would look wrong until somebody tried
         * to remove their access and found the company's money went with them.
         */
        assertThat(account.companyId())
                .as("the account must be held by the company")
                .isEqualTo(company.id());
        assertThat(account.isCompanyAccount()).isTrue();

        // The contact is recorded as who it was entered against, and that grants nothing.
        assertThat(account.customerId()).isEqualTo(customerIdOf(CONTACT));
    }

    @Test
    @DisplayName("the named contact becomes the company's first administrator, by a membership")
    void theContactIsTheFirstAdministrator() throws Exception {
        companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);

        var customer = customers.findById(customerIdOf(CONTACT)).orElseThrow();
        assertThat(customer.userType()).isEqualTo(UserType.CORPORATE);

        var held = memberships.findByCustomerIdAndRevokedAtIsNullOrderByGrantedAtAsc(customer.id());

        assertThat(held).hasSize(1);
        assertThat(held.get(0).role()).isEqualTo(CorporateRole.ADMIN);
        assertThat(held.get(0).isActive()).isTrue();
        /*
         * A named grantor. An access grant to a company's money with nobody's name
         * against it is the record that cannot answer the first question anybody asks.
         */
        assertThat(held.get(0).grantedBy()).isNotBlank();
    }

    @Test
    @DisplayName("the company's login is CORPORATE and carries the company, not retail permissions")
    void theSessionIsCorporate() throws Exception {
        MockHttpSession session = companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);

        String body =
                mvc.perform(get("/api/v1/session").session(session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.user.userType").value("CORPORATE"))
                        .andExpect(jsonPath("$.user.corporates[0].name").value(COMPANY))
                        .andExpect(jsonPath("$.user.corporates[0].role").value("ADMIN"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode payload = json.readTree(body);

        /*
         * The active company is set. Without it the portal holds a company list and no
         * company, which renders as a corporate dashboard scoped to nothing.
         */
        assertThat(payload.get("activeCorporateId").asString())
                .isEqualTo(payload.get("user").get("corporates").get(0).get("id").asString());

        var permissions =
                payload.get("user").get("permissions").valueStream().map(JsonNode::asString).toList();

        assertThat(permissions).contains("CORPORATE_VIEW");
        /*
         * AND NOT THE RETAIL SET, which is what every signed-in customer used to get from
         * a hardcoded constant. A corporate session carrying RETAIL_ACCOUNT_VIEW would
         * render a personal dashboard for a company.
         */
        assertThat(permissions).doesNotContain("RETAIL_ACCOUNT_VIEW");
    }

    @Test
    @DisplayName("the company's administrator sees and can move the company's money")
    void theAdministratorCanOperateTheAccounts() throws Exception {
        MockHttpSession session = companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);
        UUID accountId = accountNumbered("4001234567891").id();

        mvc.perform(get("/api/v1/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].availableBalance.amount").value("900000"));

        mvc.perform(deposit(accountId, session, "corp-in-1")).andExpect(status().isOk());
        mvc.perform(withdraw(accountId, session, "corp-out-1")).andExpect(status().isOk());

        // 900,000 + 1,000 − 1,000. The balance is the sum of the ledger or it is nothing.
        assertThat(customerAccounts.findById(accountId).orElseThrow().balance().minorUnits())
                .isEqualTo(900_000L);
    }

    /* ------------------------------------------------- and nobody else can */

    @Test
    @DisplayName("ONE COMPANY CANNOT SEE OR TOUCH ANOTHER'S ACCOUNTS")
    void companiesAreIsolatedFromEachOther() throws Exception {
        companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);
        UUID theirs = accountNumbered("4001234567891").id();

        MockHttpSession outsider =
                companyWithAccounts(
                        "Rwanda Mountain Tea Ltd", "director@rmt.example.rw",
                        OTHER_COMPANY_ACCOUNT);

        /*
         * Their own account and nothing else. Asserting only that the list has one entry
         * would pass against a bug that returned the WRONG single account, so the number
         * is checked too.
         */
        mvc.perform(get("/api/v1/accounts").session(outsider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].availableBalance.amount").value("5000"));

        /*
         * 404, not 403. The account exists and is none of their business, and a 403 would
         * confirm to whoever asked that there is an account at that id — the account ids
         * are in URLs, so a distinguishable answer is a way to enumerate the bank's
         * corporate customers.
         */
        mvc.perform(get("/api/v1/accounts/{id}", theirs.toString()).session(outsider))
                .andExpect(status().isNotFound());

        mvc.perform(deposit(theirs, outsider, "cross-in")).andExpect(status().isNotFound());
        mvc.perform(withdraw(theirs, outsider, "cross-out")).andExpect(status().isNotFound());

        // And none of it moved.
        assertThat(customerAccounts.findById(theirs).orElseThrow().balance().minorUnits())
                .isEqualTo(900_000L);
    }

    @Test
    @DisplayName("A PERSONAL CUSTOMER CANNOT REACH A COMPANY ACCOUNT")
    void aPersonalCustomerCannotReachACompanyAccount() throws Exception {
        companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);
        UUID theirs = accountNumbered("4001234567891").id();

        MockHttpSession personal =
                customerWithAccounts("private@example.rw", PERSONAL_ACCOUNT);

        mvc.perform(get("/api/v1/accounts").session(personal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].availableBalance.amount").value("1000"));

        mvc.perform(get("/api/v1/accounts/{id}", theirs.toString()).session(personal))
                .andExpect(status().isNotFound());
        mvc.perform(deposit(theirs, personal, "personal-in")).andExpect(status().isNotFound());
        mvc.perform(withdraw(theirs, personal, "personal-out")).andExpect(status().isNotFound());

        assertThat(customerAccounts.findById(theirs).orElseThrow().balance().minorUnits())
                .isEqualTo(900_000L);
    }

    @Test
    @DisplayName("REVOKING THE MEMBERSHIP TAKES THE ACCOUNTS AWAY, in the same session")
    void revokingMembershipRemovesAccess() throws Exception {
        MockHttpSession session = companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);
        UUID accountId = accountNumbered("4001234567891").id();

        // Working, before.
        mvc.perform(deposit(accountId, session, "before-revocation")).andExpect(status().isOk());

        /*
         * THE CASE THIS WHOLE DESIGN EXISTS FOR, and the one a `customer_id` comparison
         * would have got wrong. The account row still names this customer — that is who
         * the administrator was creating a login for — so a rule that read the row would
         * hand the company's money back to somebody whose access was deliberately taken
         * away. Revoked access that still works is worse than access never granted,
         * because somebody has already decided it should stop.
         *
         * Revoked through the repository rather than an endpoint because there is no
         * endpoint yet; the rule being tested is the read side, not who may revoke.
         */
        var membership =
                memberships
                        .findByCustomerIdAndRevokedAtIsNullOrderByGrantedAtAsc(
                                customerIdOf(CONTACT))
                        .get(0);
        membership.revoke("manager@zigama.local", "Left the company");
        memberships.save(membership);

        mvc.perform(get("/api/v1/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));

        mvc.perform(deposit(accountId, session, "after-revocation"))
                .andExpect(status().isNotFound());
        mvc.perform(withdraw(accountId, session, "after-revocation-out"))
                .andExpect(status().isNotFound());

        // The money is still the company's; it is the person's access that ended.
        assertThat(customerAccounts.findById(accountId).orElseThrow().balance().minorUnits())
                .isEqualTo(901_000L);

        // The row survives, with who ended it and why.
        var revoked = memberships.findById(membership.id()).orElseThrow();
        assertThat(revoked.isActive()).isFalse();
        assertThat(revoked.revokedBy()).isEqualTo("manager@zigama.local");
        assertThat(revoked.revocationReason()).isEqualTo("Left the company");
    }

    @Test
    @DisplayName("a revoked corporate login keeps no permissions and falls back to nothing")
    void aRevokedCorporateSessionHasNoPermissions() throws Exception {
        MockHttpSession session = companyWithAccounts(COMPANY, CONTACT, COMPANY_ACCOUNT);

        var membership =
                memberships
                        .findByCustomerIdAndRevokedAtIsNullOrderByGrantedAtAsc(
                                customerIdOf(CONTACT))
                        .get(0);
        membership.revoke("manager@zigama.local", "Left the company");
        memberships.save(membership);

        /*
         * NOT the retail set. A corporate login whose membership has ended has nothing to
         * do, and falling back to retail permissions would hand it a personal dashboard
         * and the retail transfer screens — an upgrade granted by losing access.
         */
        mvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userType").value("CORPORATE"))
                .andExpect(jsonPath("$.user.permissions", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.user.corporates", Matchers.hasSize(0)))
                /*
                 * ABSENT, not null. The API is configured with non_null inclusion, so a
                 * null property is omitted from the body entirely — which is the
                 * convention `lastLoginAt` already relies on. Asserted as absence rather
                 * than as null so this test says what the client actually receives; the
                 * client normalises it back to null in one place (sessionService) rather
                 * than each reader guessing.
                 */
                .andExpect(jsonPath("$.activeCorporateId").doesNotExist());
    }

}
