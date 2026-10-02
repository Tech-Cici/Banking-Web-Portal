package rw.bank.ibanking.onboarding;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * THE STAFF OVERVIEW COUNTS EVERY QUEUE, NOT JUST ONBOARDING.
 *
 * <p>WHAT WAS WRONG. {@code /admin/summary} returned five onboarding figures and nothing
 * else, while the service had grown six queues. So a MANAGER signing in saw counts that are
 * almost entirely an administrator's job — registrations, accounts created, applications
 * rejected — and no sign of their own work: money waiting to be released, passwords to
 * re-issue, payees to check, cards to hand over. The single link out of that panel went to
 * the registrations queue whoever was looking at it.
 *
 * <p>WHY THE FIGURES MUST BE DATABASE COUNTS, which is what these tests check rather than
 * merely that a number came back. Each queue screen fetches a BOUNDED page, so its size is
 * the page and not the backlog. An overview built from those lists would tell a manager
 * "12 waiting" with 200 waiting — a worse number than none, because it is actionable and
 * wrong.
 */
@DisplayName("The staff overview")
class StaffSummaryTest extends OnboardingIntegrationTest {

    private static final String ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4004444444444","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"60000"}]}""";

    private JsonNode summary() throws Exception {
        return json.readTree(
                mvc.perform(asStaff(MANAGER_EMAIL, get("/api/v1/admin/summary")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    @DisplayName("counts money waiting for a manager, which it never used to")
    void countsTransfersWaitingForRelease() throws Exception {
        MockHttpSession sender = customerWithAccounts("summary-sender@example.rw", ACCOUNTS);
        customerWithAccounts(
                "summary-payee@example.rw",
                """
                {"accounts":[{"accountNumber":"4004444444445","accountType":"CURRENT",\
                "currency":"RWF","openingBalance":"1000"}]}""");

        JsonNode before = summary();
        /* Nothing in flight yet, and the field exists — a missing field would read as zero
           on the screen and be indistinguishable from an empty queue. */
        org.assertj.core.api.Assertions.assertThat(before.get("transfersToRelease").asInt())
                .isZero();

        String sourceId =
                json.readTree(
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
                                .header("Idempotency-Key", "summary-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sourceAccountId":"%s",\
                                        "destinationAccountNumber":"4004444444445",\
                                        "amount":"9000","reference":"rent"}"""
                                                .formatted(sourceId)))
                .andExpect(status().isOk());

        /*
         * THE FIGURE A MANAGER OPENS THE PORTAL FOR. Money has left an account and has not
         * arrived; before this it appeared nowhere on their overview.
         */
        mvc.perform(asStaff(MANAGER_EMAIL, get("/api/v1/admin/summary")))
                .andExpect(jsonPath("$.transfersToRelease").value(1));
    }

    @Test
    @DisplayName("counts payees and card requests waiting for a check")
    void countsPayeesAndServiceRequests() throws Exception {
        MockHttpSession customer = customerWithAccounts("summary-queues@example.rw", ACCOUNTS);

        String accountId =
                json.readTree(
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

        mvc.perform(
                        post("/api/v1/beneficiaries")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"Teta Eliana","beneficiaryType":"INTERNAL",\
                                        "provider":"Zigama CSS","destination":"4004444444446",\
                                        "currency":"RWF"}"""))
                .andExpect(status().isCreated());

        mvc.perform(asStaff(MANAGER_EMAIL, get("/api/v1/admin/summary")))
                .andExpect(jsonPath("$.payeesToCheck").value(1))
                .andExpect(jsonPath("$.cardsAndChequeBooks").value(1));
    }

    @Test
    @DisplayName("a card still on the counter is still somebody's job")
    void countsReadyRequestsTooNotJustSubmitted() throws Exception {
        MockHttpSession customer = customerWithAccounts("summary-ready@example.rw", ACCOUNTS);

        String accountId =
                json.readTree(
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

        String requestId =
                json.readTree(
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
         * STILL ONE, not zero. Counting only SUBMITTED would show staff an empty queue
         * while finished cards sat on a counter waiting to be handed over — which is the
         * state a customer is actually standing in front of somebody about.
         */
        mvc.perform(asStaff(MANAGER_EMAIL, get("/api/v1/admin/summary")))
                .andExpect(jsonPath("$.cardsAndChequeBooks").value(1));
    }

    @Test
    @DisplayName("both roles may read it, because both have work in it")
    void bothRolesMayRead() throws Exception {
        /*
         * The overview shows every queue and labels the ones that are the other role's job,
         * so both roles need the whole thing. A manager who can see that nine registrations
         * are stuck can go and find an administrator; hiding the figure would make the
         * backlog somebody else's secret.
         */
        mvc.perform(asStaff(ADMIN_EMAIL, get("/api/v1/admin/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfersToRelease").exists())
                .andExpect(jsonPath("$.passwordRequests").exists());

        mvc.perform(asStaff(MANAGER_EMAIL, get("/api/v1/admin/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").exists());
    }

    @Test
    @DisplayName("is closed to a customer")
    void isClosedToCustomers() throws Exception {
        MockHttpSession customer = customerWithAccounts("summary-nosy@example.rw", ACCOUNTS);

        /* These are the bank's own figures. A customer reading them learns how many people
           bank here and how big the backlogs are. */
        mvc.perform(get("/api/v1/admin/summary").session(customer))
                .andExpect(status().isForbidden());
    }
}
