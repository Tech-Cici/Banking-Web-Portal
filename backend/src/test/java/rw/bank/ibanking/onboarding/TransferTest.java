package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.TransferStatus;

/**
 * TRANSFERS: money leaves one account, waits for a manager, and arrives at another.
 *
 * <p>WHAT THIS EXISTS TO PROVE. Every other test in this suite checks one account at a
 * time. A transfer is the first operation that touches two, so it is the first one that
 * can lose money rather than merely refuse it — and losing money is quiet. A wrong
 * refusal is a complaint the same afternoon; an amount that left one account and arrived
 * at neither is found weeks later by somebody reconciling, if anyone is.
 *
 * <p>THE INVARIANT EVERY TEST HERE CHECKS:
 *
 * <pre>
 *     sum(every balance) + sum(every pending transfer) = constant
 * </pre>
 *
 * <p>Not simply "the balances add up", because while a transfer waits the money is in
 * flight: it has left the sender and arrived nowhere, so the accounts alone are short by
 * the pending total. A bank holds that difference in a suspense account and this service
 * has none, so the invariant is asserted here instead — which is the whole reason
 * {@link #totalMoney()} exists rather than a sum over balances.
 *
 * <p>AND THE HOLD IS REAL. The sender is debited at submission, not at approval. Held
 * only at approval, a customer could submit their whole balance five times and a manager
 * could approve all five, four of them failing AFTER a manager had said yes. The test for
 * that is {@link #theSameMoneyCannotBePromisedTwice()}.
 */
@DisplayName("Transfers")
class TransferTest extends OnboardingIntegrationTest {

    private static final String SENDER = "sender@example.rw";
    private static final String PAYEE = "payee@example.rw";

    private static final String SENDER_ACCOUNT = "4001111111111";
    private static final String PAYEE_ACCOUNT = "4002222222222";

    /** RWF, opened with 100,000. RWF has NO minor unit — 100000 here is 100,000 francs. */
    private static final String SENDER_OPENED_WITH_100000 =
            """
            {"accounts":[{"accountNumber":"4001111111111","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"100000"}]}""";

    private static final String PAYEE_OPENED_WITH_5000 =
            """
            {"accounts":[{"accountNumber":"4002222222222","accountType":"SAVINGS",\
            "currency":"RWF","openingBalance":"5000"}]}""";

    /* ----------------------------------------------------------- helpers */

    /**
     * EVERY FRANC THE SERVICE KNOWS ABOUT: what the accounts hold, plus what is in flight.
     *
     * <p>The second half is the part that matters. A version of this that summed only the
     * balances would pass while a transfer was submitted and then lost, because the
     * sender's debit would have brought the total down and nothing would say it should
     * have come back up.
     */
    private long totalMoney() {
        long inAccounts =
                customerAccounts.findAll().stream()
                        .mapToLong(account -> account.balance().minorUnits())
                        .sum();

        long inFlight =
                transfers.findAll().stream()
                        .filter(transfer -> transfer.status() == TransferStatus.PENDING_APPROVAL)
                        .mapToLong(transfer -> transfer.amount().minorUnits())
                        .sum();

        return inAccounts + inFlight;
    }

    private long balanceOf(String accountNumber) {
        return accountNumbered(accountNumber).balance().minorUnits();
    }

    private CustomerAccountEntity accountNumbered(String accountNumber) {
        return customerAccounts.findByAccountNumberAndRemovedAtIsNull(accountNumber).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("No account stored for " + accountNumber));
    }

    private MockHttpServletRequestBuilder send(
            MockHttpSession session, String sourceAccountId, String to, String amount, String key) {

        return post("/api/v1/transfers")
                .session(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {"sourceAccountId":"%s","destinationAccountNumber":"%s",\
                        "amount":"%s","reference":"School fees"}"""
                                .formatted(sourceAccountId, to, amount));
    }

    /** The pending transfer's id, when there is exactly one. */
    private UUID theOnlyTransferId() {
        assertThat(transfers.findAll()).hasSize(1);
        return transfers.findAll().get(0).id();
    }

    private MockHttpServletRequestBuilder managerApproves(UUID transferId) {
        return asStaff(
                MANAGER_EMAIL,
                post("/api/v1/admin/transfers/{id}/approve", transferId.toString()));
    }

    private MockHttpServletRequestBuilder managerRejects(UUID transferId, String reason) {
        return asStaff(
                MANAGER_EMAIL,
                post("/api/v1/admin/transfers/{id}/reject", transferId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s"}""".formatted(reason)));
    }

    /** A sender with 100,000 and a payee with 5,000, both signed in. */
    private MockHttpSession aSenderAndAPayee() throws Exception {
        MockHttpSession sender = customerWithAccounts(SENDER, SENDER_OPENED_WITH_100000);
        customerWithAccounts(PAYEE, PAYEE_OPENED_WITH_5000);
        return sender;
    }

    /* ------------------------------------------------------------- tests */

    @Test
    @DisplayName("SUBMITTING TAKES THE MONEY, and it arrives nowhere until a manager decides")
    void submittingHoldsTheMoney() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        long before = totalMoney();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "30000",
                                "key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Gone from the sender...
        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(70_000L);
        // ...and NOT yet with the payee. This is the assertion that fails if the service
        // credits on submission, which would be paying out money nobody had approved.
        assertThat(balanceOf(PAYEE_ACCOUNT)).isEqualTo(5_000L);

        // Nothing lost: the 30,000 is in flight.
        assertThat(totalMoney()).isEqualTo(before);
    }

    @Test
    @DisplayName("THE HOLD IS A LEDGER ENTRY, so the customer's statement says where it went")
    void theHoldIsOnTheStatement() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        String accountId = accountNumbered(SENDER_ACCOUNT).id().toString();

        mvc.perform(send(sender, accountId, PAYEE_ACCOUNT, "30000", "key-1"))
                .andExpect(status().isOk());

        /*
         * The alternative design was a `held_minor` column and no entry until approval.
         * It would pass the balance assertion above and fail this one: the money would be
         * missing from the balance with nothing on the statement explaining it, which is
         * the complaint that arrives by telephone.
         */
        mvc.perform(
                        get("/api/v1/accounts/{id}/transactions", accountId)
                                .session(sender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(
                        jsonPath("$.content[0].description")
                                .value(Matchers.containsString("awaiting approval")));
    }

    @Test
    @DisplayName("APPROVAL DELIVERS IT, in the same transaction — there is no in-between state")
    void approvalDeliversTheMoney() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        long before = totalMoney();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "30000",
                                "key-1"))
                .andExpect(status().isOk());

        mvc.perform(managerApproves(theOnlyTransferId())).andExpect(status().isOk());

        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(70_000L);
        assertThat(balanceOf(PAYEE_ACCOUNT)).isEqualTo(35_000L);

        // Conserved, and now nothing is in flight.
        assertThat(totalMoney()).isEqualTo(before);
        assertThat(transfers.findAll().get(0).status()).isEqualTo(TransferStatus.APPROVED);
        assertThat(transfers.findAll().get(0).creditEntryId()).isNotNull();
    }

    @Test
    @DisplayName("REFUSAL GIVES IT BACK, as its own entry rather than by unwriting the debit")
    void refusalReturnsTheMoney() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        long before = totalMoney();
        String accountId = accountNumbered(SENDER_ACCOUNT).id().toString();

        mvc.perform(send(sender, accountId, PAYEE_ACCOUNT, "30000", "key-1"))
                .andExpect(status().isOk());

        mvc.perform(managerRejects(theOnlyTransferId(), "Beneficiary details unconfirmed"))
                .andExpect(status().isOk());

        // Whole again, and the payee never saw it.
        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(100_000L);
        assertThat(balanceOf(PAYEE_ACCOUNT)).isEqualTo(5_000L);
        assertThat(totalMoney()).isEqualTo(before);

        /*
         * FOUR ENTRIES, not two. The opening balance, the debit, and the reversal on the
         * sender; the payee's opening balance. The debit is NOT deleted — an entry that
         * can be unwritten is a ledger nobody can audit, and the customer should see the
         * money leave and come back with a reason rather than see nothing happen.
         */
        assertThat(accountTransactions.findAll()).hasSize(4);

        var rejected = transfers.findAll().get(0);
        assertThat(rejected.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Beneficiary details unconfirmed");
        assertThat(rejected.reversalEntryId()).isNotNull();
        assertThat(rejected.creditEntryId()).isNull();
    }

    @Test
    @DisplayName("THE SAME MONEY CANNOT BE PROMISED TWICE")
    void theSameMoneyCannotBePromisedTwice() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        String accountId = accountNumbered(SENDER_ACCOUNT).id().toString();

        // The whole balance, held.
        mvc.perform(send(sender, accountId, PAYEE_ACCOUNT, "100000", "key-1"))
                .andExpect(status().isOk());

        /*
         * THE CASE THE HOLD EXISTS FOR. Debited only at approval, this second instruction
         * would be accepted and sit in the queue, and a manager approving both would pay
         * out 200,000 from an account that held 100,000 — discovering it after having
         * already said yes. Refused here instead, to the customer, at the moment they can
         * still do something about it.
         */
        mvc.perform(send(sender, accountId, PAYEE_ACCOUNT, "100000", "key-2"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(transfers.findAll()).hasSize(1);
        assertThat(balanceOf(SENDER_ACCOUNT)).isZero();
    }

    @Test
    @DisplayName("a retry with the same key is the same transfer, not a second one")
    void aRetryIsNotASecondTransfer() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        String accountId = accountNumbered(SENDER_ACCOUNT).id().toString();

        mvc.perform(send(sender, accountId, PAYEE_ACCOUNT, "30000", "same-key"))
                .andExpect(status().isOk());
        mvc.perform(send(sender, accountId, PAYEE_ACCOUNT, "30000", "same-key"))
                .andExpect(status().isOk());

        /*
         * One transfer, one debit. Without this the sender is debited twice and the
         * second instruction waits in a manager's queue looking as legitimate as the
         * first — which is worse than a duplicated deposit, because somebody approves it.
         */
        assertThat(transfers.findAll()).hasSize(1);
        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("A DOUBLE-CLICKED APPROVE CREDITS ONCE")
    void approvingTwiceCreditsOnce() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        long before = totalMoney();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "30000",
                                "key-1"))
                .andExpect(status().isOk());

        UUID transferId = theOnlyTransferId();

        mvc.perform(managerApproves(transferId)).andExpect(status().isOk());

        /*
         * Not a 500 and not a silent second credit. A queue screen gets double-clicked,
         * and the second call has to say plainly that somebody has already decided this.
         */
        mvc.perform(managerApproves(transferId)).andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(PAYEE_ACCOUNT)).isEqualTo(35_000L);
        assertThat(totalMoney()).isEqualTo(before);
    }

    @Test
    @DisplayName("a transfer already refused cannot then be approved")
    void aRefusedTransferCannotBeApproved() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "30000",
                                "key-1"))
                .andExpect(status().isOk());

        UUID transferId = theOnlyTransferId();
        mvc.perform(managerRejects(transferId, "Not recognised")).andExpect(status().isOk());

        /*
         * The money has already gone back. Approving now would credit the payee from a
         * hold that no longer exists — money created out of nothing, which is the one
         * thing a ledger must never be able to do.
         */
        mvc.perform(managerApproves(transferId)).andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(100_000L);
        assertThat(balanceOf(PAYEE_ACCOUNT)).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("A CUSTOMER CANNOT SEND FROM AN ACCOUNT THAT IS NOT THEIRS")
    void aCustomerCannotSendFromSomebodyElsesAccount() throws Exception {
        aSenderAndAPayee();

        /* A third customer, with their own empty account, signed in. */
        MockHttpSession outsider =
                customerWithAccounts(
                        "outsider@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4003333333333","accountType":"CURRENT",\
                        "currency":"RWF","openingBalance":"0"}]}""");

        long before = totalMoney();

        /*
         * 404, not 403. The account exists and is none of their business; a 403 would
         * confirm there is an account at that id, and account ids appear in URLs.
         */
        mvc.perform(
                        send(
                                outsider,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "50000",
                                "not-mine"))
                .andExpect(status().isNotFound());

        assertThat(transfers.findAll()).isEmpty();
        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(100_000L);
        assertThat(totalMoney()).isEqualTo(before);
    }

    @Test
    @DisplayName("BOTH STATEMENTS NAME THE OTHER PARTY, and neither names the wrong one")
    void bothSidesOfATransferNameTheOtherParty() throws Exception {
        /* Built here rather than through aSenderAndAPayee, because this test needs BOTH
         * sessions: the assertion is that each side's own statement names the other. */
        MockHttpSession sender = customerWithAccounts(SENDER, SENDER_OPENED_WITH_100000);
        MockHttpSession payee = customerWithAccounts(PAYEE, PAYEE_OPENED_WITH_5000);

        UUID sourceId = accountNumbered(SENDER_ACCOUNT).id();
        UUID payeeId = accountNumbered(PAYEE_ACCOUNT).id();

        mvc.perform(send(sender, sourceId.toString(), PAYEE_ACCOUNT, "30000", "named-1"))
                .andExpect(status().isOk());
        mvc.perform(managerApproves(theOnlyTransferId())).andExpect(status().isOk());

        /*
         * ASSERTED ON THE MASKS, not the names. Every customer the test fixtures build is
         * called "Test Applicant", so a name assertion would pass just as happily if the
         * implementation wrote the wrong person's name in. The masks differ, so these two
         * assertions fail if the two sides are ever swapped — which is the mistake worth
         * catching, and the one a reader of the code cannot see.
         */
        mvc.perform(get("/api/v1/accounts/{id}/transactions", payeeId).session(payee))
                .andExpect(status().isOk())
                // Newest first: the credit that has just arrived.
                .andExpect(jsonPath("$.content[0].category").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].movementKind").value("TRANSFER_IN"))
                .andExpect(jsonPath("$.content[0].counterpartyMask").value("**** 1111"))
                .andExpect(jsonPath("$.content[0].counterpartyName").value("Test Applicant"));

        mvc.perform(get("/api/v1/accounts/{id}/transactions", sourceId).session(sender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].category").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].movementKind").value("TRANSFER_OUT"))
                .andExpect(jsonPath("$.content[0].counterpartyMask").value("**** 2222"));
    }

    @Test
    @DisplayName("A REFUND IS NOT AN ARRIVAL, and the ledger says which it is")
    void aRefusedTransferIsMarkedAsReturnedRatherThanReceived() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        UUID sourceId = accountNumbered(SENDER_ACCOUNT).id();

        mvc.perform(send(sender, sourceId.toString(), PAYEE_ACCOUNT, "30000", "refund-1"))
                .andExpect(status().isOk());
        mvc.perform(managerRejects(theOnlyTransferId(), "Beneficiary details unconfirmed"))
                .andExpect(status().isOk());

        /*
         * THE CASE THE MOVEMENT KIND EXISTS FOR. The money coming back is a CREDIT with
         * the payee's name on it, exactly like an arrival. Told apart only by direction,
         * a screen would say "you received 30,000 from Test Applicant" to the customer
         * whose payment had just been refused — a receipt for money that never moved.
         *
         * The counterparty is asserted as the PAYEE's mask, not the sender's: what the
         * customer needs to see is which payment failed, and the money returning to
         * their own account is not news about somebody else.
         */
        mvc.perform(get("/api/v1/accounts/{id}/transactions", sourceId).session(sender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].movementKind").value("TRANSFER_RETURNED"))
                .andExpect(jsonPath("$.content[0].counterpartyMask").value("**** 2222"));
    }

    @Test
    @DisplayName("A DEPOSIT HAS NO OTHER PARTY, and says so by omitting the field")
    void cashMovementsCarryNoCounterparty() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        UUID sourceId = accountNumbered(SENDER_ACCOUNT).id();

        /*
         * THE OTHER HALF OF THE RULE. A deposit is the customer and the bank, so there is
         * nobody to name — and the field must be ABSENT rather than an empty string,
         * because an empty name renders as a blank "from" on a statement line. Jackson's
         * non-null inclusion is what makes that true; this asserts it stays true.
         */
        mvc.perform(get("/api/v1/accounts/{id}/transactions", sourceId).session(sender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].category").value("CASH"))
                .andExpect(jsonPath("$.content[0].movementKind").value("CASH"))
                .andExpect(jsonPath("$.content[0].counterpartyName").doesNotExist())
                .andExpect(jsonPath("$.content[0].counterpartyMask").doesNotExist());
    }

    @Test
    @DisplayName("A CLOSED ACCOUNT OF THEIR OWN SAYS SO, rather than 'we could not find that'")
    void sendingFromTheirOwnClosedAccountSaysItIsClosed() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        CustomerAccountEntity account = accountNumbered(SENDER_ACCOUNT);
        UUID accountId = account.id();
        long before = totalMoney();

        /* The bank closes it after the customer's screen was drawn. */
        account.remove("Immaculee Mukandayisenga", "Customer asked for it to be closed.");
        customerAccounts.save(account);

        /*
         * A REFUSAL THAT NAMES THE REASON. All three ways a send can be refused used to
         * arrive as this one 404, so the likeliest of them — the customer's own account,
         * picked from a list this service produced, closed since — told them only that it
         * might not be theirs to view.
         *
         * 422 rather than 404, and the sentence names closure and says to refresh, which
         * is the one action that fixes it.
         */
        mvc.perform(send(sender, accountId.toString(), PAYEE_ACCOUNT, "10000", "closed-source"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("closed")));

        assertThat(transfers.findAll()).isEmpty();
        assertThat(totalMoney()).isEqualTo(before);
    }

    @Test
    @DisplayName("SOMEBODY ELSE'S CLOSED ACCOUNT IS STILL JUST 'NOT FOUND'")
    void somebodyElsesClosedAccountIsNotNamedAsClosed() throws Exception {
        aSenderAndAPayee();

        CustomerAccountEntity account = accountNumbered(SENDER_ACCOUNT);
        UUID accountId = account.id();
        account.remove("Immaculee Mukandayisenga", "Closed.");
        customerAccounts.save(account);

        MockHttpSession outsider =
                customerWithAccounts(
                        "outsider2@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4004444444444","accountType":"CURRENT",\
                        "currency":"RWF","openingBalance":"0"}]}""");

        /*
         * THE CASE THE SPLIT ABOVE MUST NOT LEAK. Telling an outsider that an account is
         * "closed" confirms it exists, which is exactly what the 404 on somebody else's
         * account is there to withhold. Naming closure only when the account is the
         * caller's own is what keeps that true.
         */
        mvc.perform(send(outsider, accountId.toString(), PAYEE_ACCOUNT, "10000", "closed-other"))
                .andExpect(status().isNotFound());

        assertThat(transfers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("CURRENT TO SAVINGS, SAME PERSON, by picking the account rather than typing a number")
    void aCustomerCanMoveMoneyBetweenTheirOwnAccounts() throws Exception {
        /*
         * THE MOST ORDINARY TRANSFER IN THE PRODUCT, and the endpoint could not do it.
         *
         * Submitting took a full account NUMBER only. The client is never sent one — every
         * account response carries `**** 4582` and nothing else — so a picker listing the
         * customer's own accounts had nothing to put in the field, and current-to-savings
         * was impossible through the API while looking perfectly possible on the screen.
         *
         * So the destination may also arrive as an id, accepted only for an account this
         * customer may already act on.
         */
        MockHttpSession session =
                customerWithAccounts(
                        "twoaccounts@example.rw",
                        """
                        {"accounts":[\
                        {"accountNumber":"4007777777777","accountType":"CURRENT",\
                        "currency":"RWF","openingBalance":"80000"},\
                        {"accountNumber":"4008888888888","accountType":"SAVINGS",\
                        "currency":"RWF","openingBalance":"2000"}]}""");

        long before = totalMoney();
        String current = accountNumbered("4007777777777").id().toString();
        String savings = accountNumbered("4008888888888").id().toString();

        mvc.perform(
                        post("/api/v1/transfers")
                                .session(session)
                                .with(csrf())
                                .header("Idempotency-Key", "own-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s","destinationAccountId":"%s",\
                                        "amount":"25000","reference":"To savings"}"""
                                                .formatted(current, savings)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Held, not yet arrived — the same rule as any other transfer.
        assertThat(balanceOf("4007777777777")).isEqualTo(55_000L);
        assertThat(balanceOf("4008888888888")).isEqualTo(2_000L);

        mvc.perform(managerApproves(theOnlyTransferId())).andExpect(status().isOk());

        assertThat(balanceOf("4007777777777")).isEqualTo(55_000L);
        assertThat(balanceOf("4008888888888")).isEqualTo(27_000L);
        assertThat(totalMoney()).isEqualTo(before);
    }

    @Test
    @DisplayName("A DESTINATION ID THEY CANNOT REACH IS REFUSED, so the id is not a way round the number")
    void aDestinationIdMustBeTheirOwn() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        long before = totalMoney();

        /*
         * Paying money INTO somebody else's account is how you confirm it exists, and
         * account ids appear in URLs all over the portal. If the id path skipped the
         * entitlement check, it would be a cheaper oracle than the account number the
         * typed path deliberately requires.
         */
        mvc.perform(
                        post("/api/v1/transfers")
                                .session(sender)
                                .with(csrf())
                                .header("Idempotency-Key", "probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s","destinationAccountId":"%s",\
                                        "amount":"1000","reference":"probe"}"""
                                                .formatted(
                                                        accountNumbered(SENDER_ACCOUNT)
                                                                .id()
                                                                .toString(),
                                                        accountNumbered(PAYEE_ACCOUNT)
                                                                .id()
                                                                .toString())))
                .andExpect(status().isNotFound());

        assertThat(transfers.findAll()).isEmpty();
        assertThat(totalMoney()).isEqualTo(before);
    }

    @Test
    @DisplayName("sending a picked account AND a typed number together is refused rather than guessed")
    void bothDestinationsAtOnceIsRefused() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        post("/api/v1/transfers")
                                .session(sender)
                                .with(csrf())
                                .header("Idempotency-Key", "both")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s","destinationAccountNumber":"%s",\
                                        "destinationAccountId":"%s","amount":"1000","reference":"x"}"""
                                                .formatted(
                                                        accountNumbered(SENDER_ACCOUNT)
                                                                .id()
                                                                .toString(),
                                                        PAYEE_ACCOUNT,
                                                        accountNumbered(SENDER_ACCOUNT)
                                                                .id()
                                                                .toString())))
                .andExpect(status().isUnprocessableEntity());

        assertThat(transfers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("sending neither a picked account nor a number is refused")
    void noDestinationAtAllIsRefused() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        post("/api/v1/transfers")
                                .session(sender)
                                .with(csrf())
                                .header("Idempotency-Key", "none")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s","amount":"1000","reference":"x"}"""
                                                .formatted(
                                                        accountNumbered(SENDER_ACCOUNT)
                                                                .id()
                                                                .toString())))
                .andExpect(status().isUnprocessableEntity());

        assertThat(transfers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an account cannot pay itself")
    void anAccountCannotPayItself() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                SENDER_ACCOUNT,
                                "1000",
                                "self"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(transfers.findAll()).isEmpty();
        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("an unknown account number is refused before anything is debited")
    void anUnknownDestinationIsRefused() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();
        long before = totalMoney();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                "4009999999999",
                                "1000",
                                "nowhere"))
                .andExpect(status().isNotFound());

        // Nothing held. A debit taken before the destination was checked would strand it.
        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(100_000L);
        assertThat(totalMoney()).isEqualTo(before);
        assertThat(transfers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("submitting without an idempotency key is refused")
    void anIdempotencyKeyIsRequired() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        post("/api/v1/transfers")
                                .session(sender)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s","destinationAccountNumber":"%s",\
                                        "amount":"1000","reference":"x"}"""
                                                .formatted(
                                                        accountNumbered(SENDER_ACCOUNT)
                                                                .id()
                                                                .toString(),
                                                        PAYEE_ACCOUNT)))
                // 400, not 500: a missing header is the client's mistake, stated as one.
                .andExpect(status().isBadRequest());

        assertThat(transfers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("only a MANAGER may decide a transfer")
    void onlyAManagerMayDecide() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "30000",
                                "key-1"))
                .andExpect(status().isOk());

        UUID transferId = theOnlyTransferId();

        // The ADMIN creates logins and may not release money.
        mvc.perform(
                        asStaff(
                                ADMIN_EMAIL,
                                post("/api/v1/admin/transfers/{id}/approve", transferId.toString())))
                .andExpect(status().isForbidden());

        // And a customer certainly may not approve their own instruction.
        mvc.perform(
                        post("/api/v1/admin/transfers/{id}/approve", transferId.toString())
                                .session(sender)
                                .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(balanceOf(PAYEE_ACCOUNT)).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("refusing without a reason is refused")
    void aRefusalNeedsAReason() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "30000",
                                "key-1"))
                .andExpect(status().isOk());

        mvc.perform(managerRejects(theOnlyTransferId(), "  "))
                .andExpect(status().is4xxClientError());

        // Still pending, still held. A refusal that did not happen must not move money.
        assertThat(transfers.findAll().get(0).status())
                .isEqualTo(TransferStatus.PENDING_APPROVAL);
        assertThat(balanceOf(SENDER_ACCOUNT)).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("THE LOOKUP RETURNS A REAL NAME, not a placeholder")
    void theLookupReturnsTheHolderName() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        String body =
                mvc.perform(
                                post("/api/v1/transfers/resolve")
                                        .session(sender)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"accountNumber":"%s"}""".formatted(PAYEE_ACCOUNT)))
                        .andExpect(status().isOk())
                        /*
                         * THE ACTUAL NAME. This answered the literal string "Account
                         * holder" for a while, because the lookup reused the helper that
                         * names whoever MOVED money on an account. A check that always
                         * answers the same thing is worse than no check: it looks like a
                         * safety net.
                         */
                        .andExpect(jsonPath("$.holderName").value("Test Applicant"))
                        .andExpect(jsonPath("$.maskedNumber").value("**** 2222"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        /*
         * THE FULL NUMBER MUST NOT COME BACK. The caller supplied it, so echoing it leaks
         * nothing to them — but it would put a full account number into every response
         * body, browser cache and error report that ever carries this payload.
         */
        assertThat(body).doesNotContain(PAYEE_ACCOUNT);
    }

    @Test
    @DisplayName("AND IT IS RATE LIMITED, so a list of numbers cannot become a list of names")
    void theLookupIsRateLimited() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        /*
         * The lookup is the one place in the portal that turns a number into a person.
         * Twenty an hour is far above what paying people needs and far below what walking
         * a number range needs — but only if the limit actually fires, which is what this
         * asserts. Without it the endpoint is an identity harvest with a polite comment
         * above it.
         *
         * A MISS COSTS THE SAME ALLOWANCE AS A HIT, so unknown numbers are used here:
         * charging only for hits would let somebody probe freely until they found one.
         */
        for (int i = 0; i < 20; i++) {
            mvc.perform(
                            post("/api/v1/transfers/resolve")
                                    .session(sender)
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"accountNumber":"40099999%05d"}""".formatted(i)))
                    // Unknown numbers, so 404 — but each one is counted.
                    .andExpect(status().isNotFound());
        }

        mvc.perform(
                        post("/api/v1/transfers/resolve")
                                .session(sender)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"accountNumber":"%s"}""".formatted(PAYEE_ACCOUNT)))
                .andExpect(status().isTooManyRequests())
                // Told when to come back, rather than left guessing.
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("the ledger records WHO moved the money, by name")
    void theLedgerNamesThePerson() throws Exception {
        MockHttpSession sender = aSenderAndAPayee();

        mvc.perform(
                        send(
                                sender,
                                accountNumbered(SENDER_ACCOUNT).id().toString(),
                                PAYEE_ACCOUNT,
                                "30000",
                                "key-1"))
                .andExpect(status().isOk());

        /*
         * `performed_by` used to be the literal string "Account holder" on every transfer
         * debit — a placeholder in the one field whose job is to say who moved the money,
         * sitting in a table where every other row carries a real name.
         */
        var debit =
                accountTransactions.findAll().stream()
                        .filter(e -> e.description().contains("awaiting approval"))
                        .findFirst()
                        .orElseThrow();

        assertThat(debit.performedBy()).isEqualTo("Test Applicant");
        assertThat(debit.performedBy()).isNotEqualTo("Account holder");
    }
}
