package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import rw.bank.ibanking.onboarding.domain.NotificationKind;
import rw.bank.ibanking.onboarding.repo.NotificationRepository;

/**
 * "NOTHING NEW." WAS A CLAIM THE PORTAL COULD NOT CHECK.
 *
 * <p>HOW THIS WAS FOUND, which is why the first test is the one it is. A customer asked for
 * a card. A member of staff marked it ready and typed the counter to collect it from. The
 * email arrived. And the dashboard still read <em>Nothing new.</em> — because
 * {@code /notifications} was answered by the browser's own mock from an array that starts
 * empty on every page load, and no notifications table existed at all.
 *
 * <p>An empty notification list is not a neutral state. A customer reads it as a record and
 * concludes nothing happened; the panel had no means of checking that. So the tests here
 * are, in order: the exact event that exposed it, then the two that move money, then the
 * scoping and read rules.
 */
@DisplayName("Notifications")
class NotificationTest extends OnboardingIntegrationTest {

    @Autowired private NotificationRepository notificationRepository;

    private static final String ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4005555555555","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"80000"}]}""";

    private static final String CURRENT_PASSWORD = "ZigamaCustomer9!";

    private JsonNode body(String raw) throws Exception {
        return json.readTree(raw);
    }

    /* ------------------------------------------------- the one that was found */

    @Test
    @DisplayName("a card marked ready reaches the portal, not only the inbox")
    void aReadyCardNotifiesInApp() throws Exception {
        MockHttpSession customer = customerWithAccounts("ready@example.rw", ACCOUNTS);

        String accountId =
                body(
                                mvc.perform(get("/api/v1/accounts").session(customer))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get(0)
                        .get("id")
                        .asString();

        mvc.perform(
                        post("/api/v1/service-requests")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"requestType":"CARD","accountId":"%s",\
                                        "cardType":"DEBIT"}"""
                                                .formatted(accountId)))
                .andExpect(status().isCreated());

        /* Nothing yet: asking for a card is not news to the person who asked. */
        mvc.perform(get("/api/v1/notifications").session(customer))
                .andExpect(jsonPath("$.length()").value(0));

        String requestId =
                body(
                                mvc.perform(
                                                asStaff(
                                                        ADMIN_EMAIL,
                                                        get("/api/v1/admin/service-requests")))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get(0)
                        .get("id")
                        .asString();

        mvc.perform(
                        asStaff(
                                ADMIN_EMAIL,
                                post(
                                        "/api/v1/admin/service-requests/"
                                                + requestId
                                                + "/ready"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"collectionPoint":"Head office counter 3"}"""))
                .andExpect(status().isOk());

        /*
         * THE ASSERTION THE OLD PORTAL COULD NOT HAVE PASSED. Before this, the row existed
         * in `service_requests`, the email existed in `outbox`, and the customer's own
         * dashboard said "Nothing new."
         */
        mvc.perform(get("/api/v1/notifications").session(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Your card is ready to collect"))
                /* The collection point the member of staff TYPED, not one this code chose —
                   there is no branch list in this portal. */
                .andExpect(jsonPath("$[0].body").value(
                        org.hamcrest.Matchers.containsString("Head office counter 3")))
                .andExpect(jsonPath("$[0].severity").value("INFO"))
                .andExpect(jsonPath("$[0].read").value(false))
                /* An in-app path, and the one for THIS kind: a cheque book must not send
                   somebody to the cards page. */
                .andExpect(jsonPath("$[0].link").value("/cards"));
    }

    /* ------------------------------------------------------ the money ones */

    @Test
    @DisplayName("a released transfer tells the sender, which nothing used to")
    void aReleasedTransferNotifiesTheSender() throws Exception {
        MockHttpSession sender = customerWithAccounts("sender@example.rw", ACCOUNTS);
        customerWithAccounts(
                "payee@example.rw",
                """
                {"accounts":[{"accountNumber":"4006666666666","accountType":"CURRENT",\
                "currency":"RWF","openingBalance":"1000"}]}""");

        String sourceId =
                body(
                                mvc.perform(get("/api/v1/accounts").session(sender))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get(0)
                        .get("id")
                        .asString();

        mvc.perform(
                        post("/api/v1/transfers")
                                .session(sender)
                                .with(csrf())
                                .header("Idempotency-Key", "notify-release-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s",\
                                        "destinationAccountNumber":"4006666666666",\
                                        "amount":"12000","reference":"school fees"}"""
                                                .formatted(sourceId)))
                .andExpect(status().isOk());

        String transferId =
                body(
                                mvc.perform(
                                                asStaff(
                                                        MANAGER_EMAIL,
                                                        get("/api/v1/admin/transfers")))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get(0)
                        .get("id")
                        .asString();

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/transfers/" + transferId + "/approve"))
                                .with(csrf()))
                .andExpect(status().isOk());

        /*
         * UNTIL NOW THE SENDER WAS TOLD NOTHING AT ALL. TransferService.approve sends no
         * email, so the only way to learn that one's own money had actually left was to
         * keep reloading the dashboard's "Waiting for the bank" panel until the row
         * disappeared from it.
         *
         * The amount is asserted as the GROUPED figure. A notification about somebody's
         * money reading "RWF 12000" is the kind of thing misread by a factor of ten.
         */
        mvc.perform(get("/api/v1/notifications").session(sender))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Your transfer has been sent"))
                .andExpect(jsonPath("$[0].body").value(
                        org.hamcrest.Matchers.containsString("RWF 12,000")))
                .andExpect(jsonPath("$[0].severity").value("INFO"));
    }

    @Test
    @DisplayName("a refused transfer says the money came back")
    void aRejectedTransferSaysTheMoneyIsBack() throws Exception {
        MockHttpSession sender = customerWithAccounts("refused@example.rw", ACCOUNTS);
        customerWithAccounts(
                "payee2@example.rw",
                """
                {"accounts":[{"accountNumber":"4006666666667","accountType":"CURRENT",\
                "currency":"RWF","openingBalance":"1000"}]}""");

        String sourceId =
                body(
                                mvc.perform(get("/api/v1/accounts").session(sender))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get(0)
                        .get("id")
                        .asString();

        mvc.perform(
                        post("/api/v1/transfers")
                                .session(sender)
                                .with(csrf())
                                .header("Idempotency-Key", "notify-reject-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s",\
                                        "destinationAccountNumber":"4006666666667",\
                                        "amount":"5000","reference":"rent"}"""
                                                .formatted(sourceId)))
                .andExpect(status().isOk());

        String transferId =
                body(
                                mvc.perform(
                                                asStaff(
                                                        MANAGER_EMAIL,
                                                        get("/api/v1/admin/transfers")))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get(0)
                        .get("id")
                        .asString();

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/transfers/" + transferId + "/reject"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reason":"The payee account could not be verified."}"""))
                .andExpect(status().isOk());

        /*
         * SAYS THE MONEY IS BACK, in those words, and carries the manager's reason. A
         * message that only said "refused" would leave the customer unsure whether to send
         * it again — which is how a payment gets made twice.
         */
        mvc.perform(get("/api/v1/notifications").session(sender))
                .andExpect(jsonPath("$[0].title").value("Your transfer was not sent"))
                .andExpect(jsonPath("$[0].body").value(
                        org.hamcrest.Matchers.containsString("back in your account")))
                .andExpect(jsonPath("$[0].body").value(
                        org.hamcrest.Matchers.containsString("could not be verified")))
                .andExpect(jsonPath("$[0].severity").value("WARNING"));
    }

    /* ---------------------------------------------------- severity and order */

    @Test
    @DisplayName("a password change is a SECURITY notification")
    void aPasswordChangeIsSecuritySeverity() throws Exception {
        MockHttpSession customer = customerWithAccounts("pw@example.rw", ACCOUNTS);

        mvc.perform(
                        post("/api/v1/security/password")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s",\
                                        "newPassword":"Correct-Horse-9-Battery"}"""
                                                .formatted(CURRENT_PASSWORD)))
                .andExpect(status().isOk());

        /*
         * SECURITY, which the panel pins above everything else. This notification cannot
         * prevent the change — whoever made it had the current password — so its whole
         * value is reaching somebody who did NOT make it, and it is worthless sitting
         * fourth in a list under a cheque book.
         *
         * It must also NOT carry a link to anything but the security page: a message saying
         * "if this was not you, click here" trains people to click exactly the link a
         * phishing copy supplies.
         */
        mvc.perform(get("/api/v1/notifications").session(customer))
                .andExpect(jsonPath("$[0].severity").value("SECURITY"))
                .andExpect(jsonPath("$[0].link").value("/security"))
                .andExpect(jsonPath("$[0].body").value(
                        org.hamcrest.Matchers.containsString("call the number printed")));
    }

    @Test
    @DisplayName("never carries a link out of the portal")
    void refusesAnExternalLink() throws Exception {
        /*
         * BREAK THE GUARD TO SEE IT FAIL: drop the startsWith("/") check in
         * NotificationEntity.raised. A notification carries text the bank did not author —
         * a staff member's reason, a payee name the customer typed — so an absolute URL
         * rendered out of one is phishing with the bank's own domain behind it. V20's
         * constraint says the same thing one layer down.
         */
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                rw.bank.ibanking.onboarding.domain.NotificationEntity.raised(
                                        java.util.UUID.randomUUID(),
                                        NotificationKind.BENEFICIARY_APPROVED,
                                        "Title",
                                        "Body",
                                        "https://zigama-css.example.com/reset"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inside the portal");
    }

    /* ------------------------------------------------------- scoping and read */

    @Test
    @DisplayName("is nobody else's list")
    void isScopedToTheCaller() throws Exception {
        MockHttpSession mine = customerWithAccounts("mine@example.rw", ACCOUNTS);
        MockHttpSession theirs =
                customerWithAccounts(
                        "theirs@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4008888888888","accountType":"CURRENT",\
                        "currency":"RWF","openingBalance":"1000"}]}""");

        mvc.perform(
                        post("/api/v1/security/password")
                                .session(theirs)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s",\
                                        "newPassword":"Correct-Horse-9-Battery"}"""
                                                .formatted(CURRENT_PASSWORD)))
                .andExpect(status().isOk());

        assertThat(notificationRepository.findAll()).hasSize(1);

        /* There is no customer id anywhere in this API, which is what makes this hold. */
        mvc.perform(get("/api/v1/notifications").session(mine))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("will not let one customer mark another's notification read")
    void refusesToReadSomebodyElsesNotification() throws Exception {
        MockHttpSession mine = customerWithAccounts("reader@example.rw", ACCOUNTS);
        MockHttpSession theirs =
                customerWithAccounts(
                        "owner@example.rw",
                        """
                        {"accounts":[{"accountNumber":"4008888888889","accountType":"CURRENT",\
                        "currency":"RWF","openingBalance":"1000"}]}""");

        mvc.perform(
                        post("/api/v1/security/password")
                                .session(theirs)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s",\
                                        "newPassword":"Correct-Horse-9-Battery"}"""
                                                .formatted(CURRENT_PASSWORD)))
                .andExpect(status().isOk());

        String id = notificationRepository.findAll().get(0).id().toString();

        /*
         * BREAK THE GUARD TO SEE THIS FAIL: drop the customer filter in
         * Notifications.markRead. This is not a theoretical annoyance — the entire value of
         * the password-changed row is that it is still unread and at the top when its owner
         * next signs in, and this would let anybody with a session take that away.
         */
        mvc.perform(post("/api/v1/notifications/" + id + "/read").session(mine).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(notificationRepository.findAll().get(0).isRead()).isFalse();
    }

    @Test
    @DisplayName("marks one read, and then all of them")
    void marksRead() throws Exception {
        MockHttpSession customer = customerWithAccounts("marks@example.rw", ACCOUNTS);

        mvc.perform(
                        post("/api/v1/security/password")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"%s",\
                                        "newPassword":"Correct-Horse-9-Battery"}"""
                                                .formatted(CURRENT_PASSWORD)))
                .andExpect(status().isOk());

        String id = notificationRepository.findAll().get(0).id().toString();

        mvc.perform(post("/api/v1/notifications/" + id + "/read").session(customer).with(csrf()))
                .andExpect(status().isOk())
                /* Returns the row rather than 204, so the screen renders the server's answer
                   instead of assuming the request worked. */
                .andExpect(jsonPath("$.read").value(true));

        mvc.perform(post("/api/v1/notifications/read-all").session(customer).with(csrf()))
                .andExpect(status().isOk())
                /* The whole list, so there is no window in which the page and the server
                   disagree about what the customer has seen. */
                .andExpect(jsonPath("$[0].read").value(true));
    }

    @Test
    @DisplayName("is closed without a session")
    void requiresASession() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }
}
