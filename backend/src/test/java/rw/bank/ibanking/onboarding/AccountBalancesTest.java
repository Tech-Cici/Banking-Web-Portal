package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Money in this portal: the opening balance an administrator enters, and the deposits and
 * withdrawals the customer makes afterwards.
 *
 * <p>WHAT CHANGED. This service used to hold no money — it recorded which accounts a
 * person holds and said "Not available" where a balance would go, because the ledger was
 * core banking's. The bank decided otherwise. That makes these tests the ones that matter
 * most in the repository: everything below is about somebody's actual money, and each test
 * here corresponds to a specific way real money goes missing.
 *
 * <p>THE INVARIANT UNDER ALL OF THEM: the balance is the sum of the ledger. Not
 * approximately, not eventually. Every test that moves money also checks the entry behind
 * the movement, because a balance that is right while the entries are wrong is the state
 * nobody can unpick afterwards.
 */
@DisplayName("Account balances")
class AccountBalancesTest extends OnboardingIntegrationTest {

    /** An RWF current account opened with 50,000 in it. RWF has NO minor unit. */
    private static final String OPENED_WITH_50000 =
            """
            {"accounts":[{"accountNumber":"4001234567891","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"50000"}]}""";

    /* ----------------------------------------------------------- helpers */

    private MockHttpServletRequestBuilder move(
            String verb, String accountId, String body, String key, MockHttpSession session) {
        return post("/api/v1/accounts/{id}/" + verb, accountId)
                .session(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String theOnlyAccountId() {
        return customerAccounts.findAll().get(0).id().toString();
    }

    private long balanceMinorOf(String accountId) {
        return customerAccounts.findById(UUID.fromString(accountId)).orElseThrow()
                .balance()
                .minorUnits();
    }

    /* ------------------------------------------------------------- tests */

    @Test
    @DisplayName("the opening balance the admin typed is what the customer sees")
    void theOpeningBalanceReachesTheCustomer() throws Exception {
        MockHttpSession session = customerWithAccounts("opening@example.rw", OPENED_WITH_50000);

        mvc.perform(get("/api/v1/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableBalance.amount").value("50000"))
                .andExpect(jsonPath("$[0].availableBalance.currency").value("RWF"));
    }

    @Test
    @DisplayName("THE OPENING BALANCE IS A LEDGER ENTRY, not a number set directly")
    void theOpeningBalanceIsPosted() throws Exception {
        customerWithAccounts("posted@example.rw", OPENED_WITH_50000);

        /*
         * THE WHOLE REASON THE LEDGER EXISTS. It would be simpler to assign the balance,
         * and that is exactly the thing worth refusing: a starting figure with no entry
         * behind it is a number nobody can account for, and from the very first day the
         * sum of the entries would not equal the balance.
         */
        var entries = accountTransactions.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).direction().name()).isEqualTo("CREDIT");
        assertThat(entries.get(0).amount().toPlainString()).isEqualTo("50000");
        assertThat(entries.get(0).balanceAfter().toPlainString()).isEqualTo("50000");
        assertThat(entries.get(0).description()).isEqualTo("Opening balance");
        // Who entered it. The thing anyone would want first if the figure turns out wrong.
        assertThat(entries.get(0).performedBy()).isNotBlank();
    }

    @Test
    @DisplayName("an account opened at zero posts no entry")
    void zeroOpeningPostsNothing() throws Exception {
        customerWithAccounts("zero@example.rw", ONE_CURRENT_ACCOUNT);

        /*
         * Nothing happened, so nothing is recorded. A "0 deposit" line at the top of every
         * customer's first statement is noise that teaches people to skim their statement.
         */
        assertThat(accountTransactions.findAll()).isEmpty();
        assertThat(customerAccounts.findAll().get(0).balance().minorUnits()).isZero();
    }

    @Test
    @DisplayName("a deposit moves the balance and lands on the statement")
    void aDepositIsPostedAndVisible() throws Exception {
        MockHttpSession session = customerWithAccounts("deposit@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        mvc.perform(
                        move(
                                "deposit",
                                accountId,
                                """
                                {"amount":"25000","description":"Salary"}""",
                                "dep-1",
                                session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("CREDIT"))
                .andExpect(jsonPath("$.amount.amount").value("25000"))
                .andExpect(jsonPath("$.balanceAfter.amount").value("75000"))
                // COMPLETED is true rather than convenient: the entry and the balance
                // committed together before this response existed.
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(get("/api/v1/accounts/{id}", accountId).session(session))
                .andExpect(jsonPath("$.currentBalance.amount").value("75000"));

        mvc.perform(get("/api/v1/accounts/{id}/transactions", accountId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                // Newest first.
                .andExpect(jsonPath("$.content[0].description").value("Salary"))
                .andExpect(jsonPath("$.content[0].runningBalance.amount").value("75000"));
    }

    @Test
    @DisplayName("a withdrawal takes money out")
    void aWithdrawalIsPosted() throws Exception {
        MockHttpSession session = customerWithAccounts("withdraw@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        mvc.perform(move("withdraw", accountId, """
                {"amount":"20000"}""", "wd-1", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("DEBIT"))
                .andExpect(jsonPath("$.balanceAfter.amount").value("30000"));

        assertThat(balanceMinorOf(accountId)).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("AN ACCOUNT CANNOT GO BELOW ZERO")
    void thereIsNoOverdraft() throws Exception {
        MockHttpSession session = customerWithAccounts("over@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        /*
         * There is no overdraft product, so an account that could go negative would be
         * lending money nobody approved. The refusal is in the entity and again as a CHECK
         * constraint on the column — neither of them in the browser, where it would be a
         * suggestion rather than a rule.
         */
        mvc.perform(move("withdraw", accountId, """
                {"amount":"50001"}""", "wd-over", session))
                .andExpect(status().isUnprocessableEntity());

        assertThat(balanceMinorOf(accountId))
                .as("a refused withdrawal must leave the balance untouched")
                .isEqualTo(50_000L);

        assertThat(accountTransactions.findAll())
                .as("and must not write an entry")
                .hasSize(1);
    }

    @Test
    @DisplayName("THE SAME IDEMPOTENCY KEY DOES NOT MOVE THE MONEY TWICE")
    void aRetryDoesNotDoubleTheDeposit() throws Exception {
        MockHttpSession session = customerWithAccounts("retry@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        String body = """
                {"amount":"10000","description":"Cash in"}""";

        /*
         * THE CASE THIS SERVES, and it is not hypothetical: the customer taps deposit on a
         * bad connection, the money moves, the response is lost, and the app retries. The
         * second call must find the first entry and return it — not post the money again
         * and leave a discrepancy that surfaces at a reconciliation weeks later.
         */
        String first =
                mvc.perform(move("deposit", accountId, body, "same-key", session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String second =
                mvc.perform(move("deposit", accountId, body, "same-key", session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // The SAME entry, not a second one that happens to look alike.
        assertThat(json.readTree(second).get("id").asString())
                .isEqualTo(json.readTree(first).get("id").asString());

        assertThat(balanceMinorOf(accountId)).isEqualTo(60_000L);
        assertThat(accountTransactions.findAll()).hasSize(2); // opening + one deposit
    }

    @Test
    @DisplayName("a different key is a different deposit")
    void aDifferentKeyIsANewTransaction() throws Exception {
        MockHttpSession session = customerWithAccounts("twice@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        String body = """
                {"amount":"10000"}""";

        mvc.perform(move("deposit", accountId, body, "key-a", session)).andExpect(status().isOk());
        mvc.perform(move("deposit", accountId, body, "key-b", session)).andExpect(status().isOk());

        /*
         * The mirror of the test above, and it is the one that proves that test is not
         * passing for the wrong reason. If deduplication keyed on the amount rather than
         * the key, the retry test would pass while this one — a customer genuinely paying
         * in the same amount twice — would silently lose the second deposit.
         */
        assertThat(balanceMinorOf(accountId)).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("a deposit without an idempotency key is refused")
    void theKeyIsRequired() throws Exception {
        MockHttpSession session = customerWithAccounts("nokey@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        /*
         * Refused rather than accepted with a generated key. A generated fallback defeats
         * the whole mechanism: the retry after a lost response arrives with a fresh key
         * and posts the money a second time. A client that sends no key is a client with
         * that bug, and it is better to fail it here than to double a customer's deposit.
         */
        mvc.perform(
                        post("/api/v1/accounts/{id}/deposit", accountId)
                                .session(session)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"amount":"1000"}"""))
                .andExpect(status().isBadRequest());

        assertThat(balanceMinorOf(accountId)).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("RWF HAS NO MINOR UNIT, so a decimal amount is refused")
    void rwfTakesNoDecimals() throws Exception {
        MockHttpSession session = customerWithAccounts("scale@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        /*
         * Not rounded — refused. Assuming two decimal places would make every Rwandan
         * franc amount a hundred times too small or too large depending on which end of
         * the conversion got it wrong, and quietly turning 500.50 into 500 or 501 is a
         * policy decision about somebody's money that nobody made.
         *
         * The shape check in the controller lets this through on purpose; the currency is
         * the authority on scale, and it is Money.parse that refuses it.
         */
        mvc.perform(move("deposit", accountId, """
                {"amount":"500.50"}""", "scale-1", session))
                .andExpect(status().isUnprocessableEntity());

        assertThat(balanceMinorOf(accountId)).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("zero and negative amounts are refused")
    void nothingIsNotATransaction() throws Exception {
        MockHttpSession session = customerWithAccounts("zeroamt@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        mvc.perform(move("deposit", accountId, """
                {"amount":"0"}""", "zero-1", session))
                .andExpect(status().isUnprocessableEntity());

        // Rejected by shape before it reaches the service: the minus sign is not in the pattern.
        mvc.perform(move("deposit", accountId, """
                {"amount":"-100"}""", "neg-1", session))
                .andExpect(status().isBadRequest());

        assertThat(accountTransactions.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("ONE CUSTOMER CANNOT DEPOSIT INTO — OR DRAIN — ANOTHER'S ACCOUNT")
    void movementsAreScopedToTheCaller() throws Exception {
        customerWithAccounts("owner@example.rw", OPENED_WITH_50000);
        String theirAccountId = theOnlyAccountId();

        MockHttpSession intruder =
                customerWithAccounts(
                        "intruder@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4009999999999","accountType":"SAVINGS",\
                        "currency":"RWF","openingBalance":"100"}]}""");

        /*
         * 404, not 403. "You may not touch that" would confirm to a stranger that the id
         * they guessed belongs to a real account — and the account id is the only thing
         * standing between them and somebody else's money.
         */
        mvc.perform(move("withdraw", theirAccountId, """
                {"amount":"50000"}""", "theft-1", intruder))
                .andExpect(status().isNotFound());

        assertThat(balanceMinorOf(theirAccountId))
                .as("the owner's balance must be untouched")
                .isEqualTo(50_000L);
    }

    @Test
    @DisplayName("a customer cannot move money before replacing the temporary password")
    void aTemporarySessionCannotMoveMoney() throws Exception {
        String email = "temp@example.rw";
        String applicationId = registerApplicant(email);
        mvc.perform(createAccountRequest(applicationId, OPENED_WITH_50000))
                .andExpect(status().isOk());

        String customerId = customers.findAll().get(0).id().toString();
        String temporary = approveAndReadTemporaryPassword(customerId, email);
        String accountId = theOnlyAccountId();

        MockHttpSession halfWay = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(halfWay)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}"""
                                                .formatted(email, temporary)))
                .andExpect(status().isOk());

        /*
         * The session is authenticated but holds MUST_CHANGE_PASSWORD, so it is not
         * ROLE_CUSTOMER yet. The password it used was typed into an email by a member of
         * staff; anybody who read that email must not be able to spend the money with it.
         */
        mvc.perform(move("withdraw", accountId, """
                {"amount":"1000"}""", "temp-1", halfWay))
                .andExpect(status().isForbidden());

        assertThat(balanceMinorOf(accountId)).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("an anonymous caller cannot move money")
    void anonymousCannotMoveMoney() throws Exception {
        customerWithAccounts("anon@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        mvc.perform(
                        post("/api/v1/accounts/{id}/deposit", accountId)
                                .with(csrf())
                                .header("Idempotency-Key", "anon-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"amount":"1000"}"""))
                .andExpect(status().isUnauthorized());

        assertThat(balanceMinorOf(accountId)).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("recent activity spans the customer's accounts and stops at their own")
    void recentActivityIsScoped() throws Exception {
        MockHttpSession session =
                customerWithAccounts(
                        "recent@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4001234567891","accountType":"CURRENT",\
                        "currency":"RWF","openingBalance":"50000"},\
                        {"accountNumber":"4009876543210","accountType":"SAVINGS",\
                        "currency":"RWF","openingBalance":"10000"}]}""");

        // Somebody else, with activity of their own that must not appear.
        customerWithAccounts("stranger@example.rw", """
                {"accounts":[{"accountNumber":"4005555555555","accountType":"CURRENT",\
                "currency":"RWF","openingBalance":"999999"}]}""");

        mvc.perform(get("/api/v1/transactions/recent").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.amount.amount == '999999')]").isEmpty());
    }

    @Test
    @DisplayName("THE FULL ACCOUNT NUMBER NEVER APPEARS IN A MOVEMENT RESPONSE")
    void theNumberNeverLeaks() throws Exception {
        MockHttpSession session = customerWithAccounts("leak@example.rw", OPENED_WITH_50000);
        String accountId = theOnlyAccountId();

        String receipt =
                mvc.perform(move("deposit", accountId, """
                        {"amount":"1000"}""", "leak-1", session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String statement =
                mvc.perform(get("/api/v1/accounts/{id}/transactions", accountId).session(session))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        /*
         * The RAW BODY, because the risk is a field nobody thought about. And the
         * idempotency key too: it is the client's, and anyone who saw a receipt carrying
         * it could replay that submission.
         */
        assertThat(receipt).doesNotContain("4001234567891").doesNotContain("leak-1");
        assertThat(statement).doesNotContain("4001234567891").doesNotContain("leak-1");
    }
}
