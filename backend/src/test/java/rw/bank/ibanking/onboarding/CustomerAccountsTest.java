package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The whole flow, as the bank actually works it.
 *
 * <p>An administrator opens a registration and — reading Zigama's own records, which this
 * service has no view of — types the accounts that person holds: a current account, a
 * savings account, each with its number. They press create. A manager approves. The
 * customer signs in and sees exactly those accounts.
 *
 * <p>THIS REPLACES TWO WRONG MODELS. The first recorded "opening requests" the customer
 * never saw, waiting on a core-banking integration that has nothing to do; the second put
 * account numbers behind a separate manager-only screen. Both made the administrator do
 * their real job somewhere else and then asked them to do a made-up one here.
 *
 * <p>The test that matters most is {@link #theCustomerSeesTheAccountsTheAdminEntered()}:
 * it walks the entire chain with real credentials and a real session, because every step
 * in between was individually green while the thing the bank wanted did not work.
 */
@DisplayName("Customer accounts")
class CustomerAccountsTest extends OnboardingIntegrationTest {

    private static final String ADMIN = "admin@zigama.local";
    private static final String MANAGER = "manager@zigama.local";
    private static final String STAFF_PASSWORD = "ZigamaStaff1";

    private static final String CURRENT_NUMBER = "4001234567891";
    private static final String SAVINGS_NUMBER = "4009876543210";

    /** Both accounts, as the admin screen posts them. */
    private static final String CURRENT_AND_SAVINGS =
            """
            {"accounts":[{"accountNumber":"4001234567891","accountType":"CURRENT","currency":"RWF"},
                         {"accountNumber":"4009876543210","accountType":"SAVINGS","currency":"RWF"}]}""";

    /* ----------------------------------------------------------- helpers */

    private MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
        return request.with(httpBasic(user, STAFF_PASSWORD));
    }

    /** Registers somebody and returns the application id. */
    private String register(String email) throws Exception {
        String start =
                """
                {"accountNumber":"1234567890","nationalId":"1199570099999999",
                 "dateOfBirth":"1990-01-01","phone":"0781999888",
                 "email":"%s","fullName":"Test Applicant"}
                """
                        .formatted(email);

        JsonNode challenge =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/registration/personal/start")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(start))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        var message = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).get(0);
        var matcher = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(message.body());
        assertThat(matcher.find()).isTrue();

        JsonNode verified =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/registration/personal/verify")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"challengeId":"%s","code":"%s"}"""
                                                                .formatted(
                                                                        challenge
                                                                                .get("challengeId")
                                                                                .asString(),
                                                                        matcher.group(1))))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        mvc.perform(
                        post("/api/v1/registration/personal/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"verificationToken":"%s"}"""
                                                .formatted(
                                                        verified.get("verificationToken")
                                                                .asString())))
                .andExpect(status().isCreated());

        return applications.findAll().stream()
                .filter(a -> a.email().equalsIgnoreCase(email))
                .findFirst()
                .orElseThrow()
                .id()
                .toString();
    }

    private MockHttpServletRequestBuilder createAccount(String applicationId, String body) {
        return as(
                ADMIN,
                post("/api/v1/admin/applications/{id}/create-account", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    /** The temporary password out of the approval email. */
    private String approveAndReadPassword(String customerId, String email) throws Exception {
        mvc.perform(as(MANAGER, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk());

        var mail = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).get(0);
        var matcher =
                java.util.regex.Pattern.compile("Temporary password: (\\S+)").matcher(mail.body());
        assertThat(matcher.find())
                .as("the approval email should carry the temporary password")
                .isTrue();
        return matcher.group(1);
    }

    /* ------------------------------------------------------------- tests */

    @Test
    @DisplayName("the customer signs in and sees the accounts the admin entered")
    void theCustomerSeesTheAccountsTheAdminEntered() throws Exception {
        String email = "flow@example.rw";
        String applicationId = register(email);

        // The administrator types both accounts and creates the login.
        mvc.perform(createAccount(applicationId, CURRENT_AND_SAVINGS)).andExpect(status().isOk());

        String customerId = customers.findAll().get(0).id().toString();
        String temporary = approveAndReadPassword(customerId, email);

        // The customer signs in and replaces the temporary password.
        MockHttpSession session = signInAndSettle(email, temporary);

        /*
         * THE POINT OF THE WHOLE FEATURE. Two accounts in, two accounts out, on the
         * customer's own session — and masked, because the full number must never reach a
         * browser.
         */
        mvc.perform(get("/api/v1/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountType").value("CURRENT"))
                .andExpect(jsonPath("$[0].maskedNumber").value("**** 7891"))
                .andExpect(jsonPath("$[0].nickname").value("Current"))
                .andExpect(jsonPath("$[1].accountType").value("SAVINGS"))
                .andExpect(jsonPath("$[1].maskedNumber").value("**** 3210"));
    }

    @Test
    @DisplayName("the customer can open one of their accounts, and not anybody else's")
    void oneAccountIsScopedToo() throws Exception {
        String mineEmail = "mine@example.rw";
        String theirsEmail = "theirs@example.rw";

        String mineApp = register(mineEmail);
        mvc.perform(createAccount(mineApp, CURRENT_AND_SAVINGS)).andExpect(status().isOk());
        String mineId =
                customers.findAll().stream()
                        .filter(c -> c.email().equalsIgnoreCase(mineEmail))
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .toString();
        String temporary = approveAndReadPassword(mineId, mineEmail);

        String theirsApp = register(theirsEmail);
        mvc.perform(
                        createAccount(
                                theirsApp,
                                """
                                {"accounts":[{"accountNumber":"4008888888888",
                                              "accountType":"CURRENT","currency":"RWF"}]}"""))
                .andExpect(status().isOk());

        String theirAccountId =
                customerAccounts.findAll().stream()
                        .filter(a -> a.maskedNumber().endsWith("8888"))
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .toString();
        String myAccountId =
                customerAccounts.findAll().stream()
                        .filter(a -> a.maskedNumber().endsWith("7891"))
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .toString();

        MockHttpSession session = signInAndSettle(mineEmail, temporary);

        mvc.perform(get("/api/v1/accounts/{id}", myAccountId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedNumber").value("**** 7891"));

        /*
         * 404, not 403. "You may not see that" would confirm to a stranger that the id
         * they guessed is real; not-found says nothing either way.
         */
        mvc.perform(get("/api/v1/accounts/{id}", theirAccountId).session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an account with no movements has an empty statement, not a missing one")
    void transactionsAreEmptyRatherThanAbsent() throws Exception {
        String email = "ledger@example.rw";
        String applicationId = register(email);
        mvc.perform(createAccount(applicationId, CURRENT_AND_SAVINGS)).andExpect(status().isOk());

        String customerId = customers.findAll().get(0).id().toString();
        String temporary = approveAndReadPassword(customerId, email);
        String accountId = customerAccounts.findAll().get(0).id().toString();

        MockHttpSession session = signInAndSettle(email, temporary);

        /*
         * Empty because nothing has happened on this account — it was opened at zero, and
         * an opening balance of nothing posts no entry rather than a "0.00 deposit" line
         * on every customer's first statement.
         *
         * Distinct from the endpoint being missing, which is what it used to be: a
         * signed-in customer passes the catch-all, so an unmapped path returns 404, and
         * the screen renders that as "we could not find that account" beside an account
         * that is listed right above it.
         */
        mvc.perform(get("/api/v1/accounts/{id}/transactions", accountId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("an account with no opening balance shows zero, not 'not available'")
    void anAccountOpenedAtZeroSaysZero() throws Exception {
        /*
         * THIS TEST USED TO ASSERT THE OPPOSITE, and the reversal is the feature.
         *
         * It walked the response tree and failed if any field name contained "balance" or
         * "amount", because this service held no money and a zero would have been a
         * statement about somebody's funds that nothing here knew. The bank has since
         * decided the portal holds the balances, so a zero is now the truth: the account
         * was opened with nothing in it and nothing has been paid in.
         *
         * The old check is gone rather than weakened. A test that asserts the absence of a
         * field the product now requires is not a safety net; it is a trap for whoever
         * adds the field.
         */
        MockHttpSession session = customerWithAccounts("holder@example.rw", CURRENT_AND_SAVINGS);

        mvc.perform(get("/api/v1/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableBalance.amount").value("0"))
                .andExpect(jsonPath("$[0].availableBalance.currency").value("RWF"))
                .andExpect(jsonPath("$[0].currentBalance.amount").value("0"))
                .andExpect(jsonPath("$[0].balanceAsOf").isNotEmpty())
                // Was missing entirely, which read as false and disabled every debit.
                .andExpect(jsonPath("$[0].debitAllowed").value(true));
    }

    @Test
    @DisplayName("ONE CUSTOMER CANNOT SEE ANOTHER'S ACCOUNTS")
    void accountsAreScopedToTheCaller() throws Exception {
        String firstEmail = "one@example.rw";
        String secondEmail = "two@example.rw";

        String firstApp = register(firstEmail);
        mvc.perform(createAccount(firstApp, CURRENT_AND_SAVINGS)).andExpect(status().isOk());
        String firstId =
                customers.findAll().stream()
                        .filter(c -> c.email().equalsIgnoreCase(firstEmail))
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .toString();
        approveAndReadPassword(firstId, firstEmail);

        String secondApp = register(secondEmail);
        mvc.perform(
                        createAccount(
                                secondApp,
                                """
                                {"accounts":[{"accountNumber":"4005555555555",
                                              "accountType":"SAVINGS","currency":"USD"}]}"""))
                .andExpect(status().isOk());
        String secondId =
                customers.findAll().stream()
                        .filter(c -> c.email().equalsIgnoreCase(secondEmail))
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .toString();
        String temporary = approveAndReadPassword(secondId, secondEmail);

        MockHttpSession session = signInAndSettle(secondEmail, temporary);

        /*
         * The customer id comes from the principal and never from the request, so there is
         * no parameter to tamper with. This asserts the consequence: the second customer
         * sees their one account and neither of the first customer's.
         */
        mvc.perform(get("/api/v1/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].maskedNumber").value("**** 5555"));
    }

    @Test
    @DisplayName("an anonymous caller gets no accounts")
    void anonymousSeesNothing() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the full account number never appears in a staff response")
    void theNumberNeverLeaves() throws Exception {
        String applicationId = register("secret@example.rw");

        String created =
                mvc.perform(createAccount(applicationId, CURRENT_AND_SAVINGS))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        /*
         * The RAW BODY, because the risk is a field nobody thought about — a future
         * addition to CustomerSummary, a debug property, an error that echoes the request.
         * Checking that `accountNumber` is absent would only catch the one that already
         * has a name.
         */
        assertThat(created).doesNotContain(CURRENT_NUMBER).doesNotContain(SAVINGS_NUMBER);

        String list =
                mvc.perform(as(MANAGER, get("/api/v1/admin/customers")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(list).doesNotContain(CURRENT_NUMBER).doesNotContain(SAVINGS_NUMBER);
        assertThat(list).contains("**** 7891");
    }

    @Test
    @DisplayName("staff see the accounts on the customer afterwards")
    void staffSeeThemLater() throws Exception {
        String applicationId = register("later@example.rw");
        mvc.perform(createAccount(applicationId, CURRENT_AND_SAVINGS)).andExpect(status().isOk());

        mvc.perform(as(MANAGER, get("/api/v1/admin/customers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountMasks.length()").value(2))
                .andExpect(jsonPath("$[0].accounts.length()").value(2))
                .andExpect(jsonPath("$[0].accounts[0].accountType").value("CURRENT"))
                .andExpect(jsonPath("$[0].accounts[0].assignedBy").isNotEmpty());
    }

    @Test
    @DisplayName("an account already on another customer is refused, and nothing is saved")
    void anAccountBelongsToOnePerson() throws Exception {
        String firstApp = register("first@example.rw");
        mvc.perform(createAccount(firstApp, CURRENT_AND_SAVINGS)).andExpect(status().isOk());

        String secondApp = register("second@example.rw");

        /*
         * The second account in this request is fine; the first is already somebody
         * else's. The whole request must fail, and the customer must not be created — a
         * partial success would leave a login with half its accounts and no sign of why.
         */
        mvc.perform(
                        createAccount(
                                secondApp,
                                """
                                {"accounts":[{"accountNumber":"4001234567891",
                                              "accountType":"CURRENT","currency":"RWF"},
                                             {"accountNumber":"4007777777777",
                                              "accountType":"SAVINGS","currency":"RWF"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(
                        jsonPath("$.message")
                                .value(org.hamcrest.Matchers.containsString("another customer")));

        assertThat(customerAccounts.findAll())
                .as("only the first customer's two accounts should exist")
                .hasSize(2);
        assertThat(customers.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("at least one account is required")
    void atLeastOneAccount() throws Exception {
        String applicationId = register("empty@example.rw");

        mvc.perform(createAccount(applicationId, """
                {"accounts":[]}"""))
                .andExpect(status().isBadRequest());

        assertThat(customers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a malformed account number is refused")
    void badNumbersAreRefused() throws Exception {
        String applicationId = register("bad@example.rw");

        mvc.perform(
                        createAccount(
                                applicationId,
                                """
                                {"accounts":[{"accountNumber":"123",
                                              "accountType":"CURRENT","currency":"RWF"}]}"""))
                .andExpect(status().isBadRequest());

        assertThat(customerAccounts.findAll()).isEmpty();
        assertThat(customers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a manager can remove an account typed against the wrong person")
    void removingCorrectsAMistake() throws Exception {
        String applicationId = register("wrong@example.rw");
        mvc.perform(createAccount(applicationId, CURRENT_AND_SAVINGS)).andExpect(status().isOk());

        String customerId = customers.findAll().get(0).id().toString();
        String accountId = customerAccounts.findAll().get(0).id().toString();

        mvc.perform(
                        as(
                                MANAGER,
                                post(
                                                "/api/v1/admin/customers/{id}/accounts/{accountId}/remove",
                                                customerId,
                                                accountId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"reason":"Typed against the wrong customer."}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountMasks.length()").value(1));

        /*
         * The row survives. Showing one customer another's account is the most serious
         * mistake this screen can make, and deleting the evidence is the last thing
         * anybody would want afterwards.
         */
        var all = customerAccounts.findAll();
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(a -> !a.isActive()).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an administrator cannot remove an account")
    void onlyAManagerRemoves() throws Exception {
        String applicationId = register("adminremove@example.rw");
        mvc.perform(createAccount(applicationId, CURRENT_AND_SAVINGS)).andExpect(status().isOk());

        String customerId = customers.findAll().get(0).id().toString();
        String accountId = customerAccounts.findAll().get(0).id().toString();

        mvc.perform(
                        as(
                                ADMIN,
                                post(
                                                "/api/v1/admin/customers/{id}/accounts/{accountId}/remove",
                                                customerId,
                                                accountId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"reason":"Trying it on."}""")))
                .andExpect(status().isForbidden());

        assertThat(customerAccounts.findAll().get(0).isActive()).isTrue();
    }


    @Test
    @DisplayName("A CUSTOMER CAN READ THEIR OWN FULL ACCOUNT NUMBER, because otherwise they cannot be paid")
    void aCustomerCanReadTheirOwnAccountNumber() throws Exception {
        MockHttpSession session = customerWithAccounts("owner@example.rw", ONE_CURRENT_ACCOUNT);
        String accountId = customerAccounts.findAll().get(0).id().toString();

        /*
         * THE BUG THIS FIXES. The mask was applied everywhere, including to the account's
         * own holder, while the transfer form told senders to type "the full number, not
         * the masked one". So receiving money was impossible: to be paid, a customer had
         * to read out a number their own bank would not show them.
         */
        mvc.perform(get("/api/v1/accounts/{id}/number", accountId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("4001111111111"));
    }

    @Test
    @DisplayName("but NOT somebody else's, and not from the list or the detail payload")
    void theFullNumberGoesNowhereElse() throws Exception {
        MockHttpSession owner = customerWithAccounts("holder@example.rw", ONE_CURRENT_ACCOUNT);
        String accountId = customerAccounts.findAll().get(0).id().toString();

        /*
         * THE LIST AND THE DETAIL MUST STAY MASKED. A list response carrying every full
         * number is one leak away from being every full number, and a detail response
         * carrying it hands the client the number whether or not anybody asked for it.
         * The reveal is its own request so it appears in exactly one payload, on purpose.
         */
        String list =
                mvc.perform(get("/api/v1/accounts").session(owner))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(list).contains("**** 1111").doesNotContain("4001111111111");

        String detail =
                mvc.perform(get("/api/v1/accounts/{id}", accountId).session(owner))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(detail).contains("**** 1111").doesNotContain("4001111111111");

        /*
         * And somebody else asking gets 404 — the same entitlement check that decides
         * whether money may move. 404 rather than 403: the account exists and is none of
         * their business, and account ids appear in URLs.
         */
        MockHttpSession outsider =
                customerWithAccounts(
                        "outsider@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4009090909090","accountType":"SAVINGS",\
                        "currency":"RWF","openingBalance":"0"}]}""");

        mvc.perform(get("/api/v1/accounts/{id}/number", accountId).session(outsider))
                .andExpect(status().isNotFound());
    }
}
