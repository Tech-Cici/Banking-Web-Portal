package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import rw.bank.ibanking.onboarding.domain.ServiceRequestStatus;
import tools.jackson.databind.JsonNode;

/**
 * ASKING THE BANK FOR A CARD OR A CHEQUE BOOK.
 *
 * <p>WHAT WAS WRONG BEFORE ANY OF THIS EXISTED, because it is what the tests are aimed at.
 * Both screens posted to endpoints only the browser's mock answered, and that mock pushed a
 * row onto an in-memory array which starts empty on every page load — so the request
 * reached nobody, no staff screen existed to show it, and the reference the customer was
 * shown stopped existing when they refreshed.
 *
 * <p>And the screen promised three things that were not the bank's: a reference minted from
 * the clock, "about five working days to print" hardcoded in a component, and a named branch
 * drawn from six names the front end had invented.
 *
 * <p>So the properties worth testing are:
 *
 * <ul>
 *   <li>A REQUEST SURVIVES. The server returns it, it is on the customer's list, and it is
 *       on the staff queue — the failure that made the whole feature theatre.
 *   <li>NOTHING STATES A COLLECTION POINT until a member of staff types one.
 *   <li>THE LIFECYCLE CANNOT BE SKIPPED: nothing is collected before it is ready, and a
 *       second decision on a settled request is refused.
 *   <li>A REQUEST IS SCOPED TO ITS OWNER, and cannot name somebody else's account.
 *   <li>CHEQUE BOOKS ARE CURRENT-ACCOUNT ONLY, on the server and not just in a dropdown.
 * </ul>
 */
@DisplayName("Cards and cheque books")
class ServiceRequestTest extends OnboardingIntegrationTest {

    private static final String CURRENT_ONLY =
            """
            {"accounts":[{"accountNumber":"4101000000001","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"50000"}]}""";

    private static final String SAVINGS_ONLY =
            """
            {"accounts":[{"accountNumber":"4102000000002","accountType":"SAVINGS",\
            "currency":"RWF","openingBalance":"50000"}]}""";

    /* ------------------------------------------------------------- helpers */

    private String firstAccountIdOf(MockHttpSession session) throws Exception {
        String body =
                mvc.perform(get("/api/v1/accounts").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).get(0).get("id").asString();
    }

    /** Asks for a card and returns the created row's JSON. */
    private JsonNode askForCard(MockHttpSession session, String accountId) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/service-requests")
                                        .session(session)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"requestType":"CARD","cardType":"DEBIT",\
                                                "accountId":"%s"}"""
                                                        .formatted(accountId)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("SUBMITTED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body);
    }

    private JsonNode staffQueue() throws Exception {
        String body =
                mvc.perform(asStaff(ADMIN_EMAIL, get("/api/v1/admin/service-requests")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body);
    }

    /* ================================================ the request survives */

    @Test
    @DisplayName("A REQUEST REACHES THE BANK — it is on the customer's list AND the staff queue")
    void aRequestReachesTheBank() throws Exception {
        MockHttpSession customer = customerWithAccounts("card.asker@example.rw", CURRENT_ONLY);
        String accountId = firstAccountIdOf(customer);

        JsonNode created = askForCard(customer, accountId);
        String reference = created.get("reference").asString();

        /*
         * THIS IS THE REGRESSION THAT MATTERS. The old arrangement answered from the
         * browser's own memory, so both of these lists were empty the moment the page
         * reloaded — the customer's reference referred to nothing and staff had no queue.
         */
        mvc.perform(get("/api/v1/service-requests").session(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reference").value(reference));

        JsonNode queue = staffQueue();
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("reference").asString()).isEqualTo(reference);
        assertThat(queue.get(0).get("details").asString()).contains("Debit card");
    }

    @Test
    @DisplayName("THE REFERENCE AVOIDS THE CHARACTERS PEOPLE MISHEAR")
    void theReferenceIsReadableAloud() throws Exception {
        MockHttpSession customer = customerWithAccounts("ref.reader@example.rw", CURRENT_ONLY);
        String reference =
                askForCard(customer, firstAccountIdOf(customer)).get("reference").asString();

        /*
         * Its whole job is to survive being read down a bad line and typed in by somebody
         * else, so no O/0, I/1 or S/5. Checked rather than trusted, because the alphabet
         * is a string constant somebody will one day "tidy up".
         */
        assertThat(reference).startsWith("CRD-");
        assertThat(reference.substring(4)).hasSize(6).doesNotContainAnyWhitespaces();
        assertThat(reference.substring(4)).doesNotContainPattern("[O0I1S5]");
    }

    /* ========================================== nothing promises a branch */

    @Test
    @DisplayName("NO COLLECTION POINT EXISTS until a member of staff types one")
    void noCollectionPointUntilStaffSayOne() throws Exception {
        MockHttpSession customer = customerWithAccounts("branchless@example.rw", CURRENT_ONLY);
        askForCard(customer, firstAccountIdOf(customer));

        /*
         * THE WHOLE POINT OF THE REDESIGN. The old screen let the customer choose from six
         * branch names the front end had invented and then told them to take their ID
         * there. Absent is the only truthful value before the bank has produced the thing,
         * and the field being absent is what stops a screen rendering a branch name.
         *
         * Absent rather than null: the service sets `default-property-inclusion: non_null`.
         */
        String mine =
                mvc.perform(get("/api/v1/service-requests").session(customer))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(json.readTree(mine).get(0).has("collectionPoint")).isFalse();

        assertThat(staffQueue().get(0).has("collectionPoint")).isFalse();

        /* And no branch name the portal could have guessed appears anywhere in either. */
        assertThat(mine).doesNotContain("Nyarugenge").doesNotContain("Kimironko");
    }

    @Test
    @DisplayName("MARKING IT READY REQUIRES A COLLECTION POINT, and emails what was typed")
    void readyRequiresACollectionPoint() throws Exception {
        MockHttpSession customer = customerWithAccounts("collector@example.rw", CURRENT_ONLY);
        String id = askForCard(customer, firstAccountIdOf(customer)).get("id").asString();

        /*
         * 400 rather than 422: @NotBlank on the request record fires at the controller
         * boundary before the service is reached. The entity's own guard is the second
         * line, for any future caller not coming through this endpoint.
         */
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/ready")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"collectionPoint":"   "}""")))
                .andExpect(status().isBadRequest());

        assertThat(serviceRequests.findAll().get(0).status())
                .isEqualTo(ServiceRequestStatus.SUBMITTED);

        /* With one supplied, it goes through and the customer is told — in those words. */
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/ready")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"collectionPoint":"Head office, counter 3"}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.collectionPoint").value("Head office, counter 3"));

        var mail = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc("collector@example.rw");
        assertThat(mail).isNotEmpty();
        String body = mail.get(0).body();
        assertThat(body).contains("Head office, counter 3");
        assertThat(body).containsIgnoringCase("photo identification");
        /* The bank's own words, and no invented branch smuggled in alongside them. */
        assertThat(body).doesNotContain("Nyarugenge");
        assertThat(body).doesNotContain("five working days");
    }

    /* ================================================= the lifecycle holds */

    @Test
    @DisplayName("NOTHING IS COLLECTED BEFORE IT IS READY")
    void collectionNeedsReadinessFirst() throws Exception {
        MockHttpSession customer = customerWithAccounts("eager@example.rw", CURRENT_ONLY);
        String id = askForCard(customer, firstAccountIdOf(customer)).get("id").asString();

        /*
         * A request going straight from SUBMITTED to COLLECTED records that something was
         * handed over before it was made — and would leave the collection point null
         * against a constraint that requires it, so the database would reject it anyway.
         * Refusing here makes it a sentence instead of a 500.
         */
        mvc.perform(
                        asStaff(
                                ADMIN_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/collected")
                                        .with(csrf())))
                .andExpect(status().isUnprocessableEntity());

        assertThat(serviceRequests.findAll().get(0).status())
                .isEqualTo(ServiceRequestStatus.SUBMITTED);
    }

    @Test
    @DisplayName("A SECOND DECISION IS REFUSED, so two staff cannot overwrite each other")
    void onlyOneDecisionPerRequest() throws Exception {
        MockHttpSession customer = customerWithAccounts("raced@example.rw", CURRENT_ONLY);
        String id = askForCard(customer, firstAccountIdOf(customer)).get("id").asString();

        mvc.perform(
                        asStaff(
                                ADMIN_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/ready")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"collectionPoint":"Head office"}""")))
                .andExpect(status().isOk());

        /*
         * Without the guard the second write replaces the first one's name AND collection
         * point — so the audit row credits whoever was slower, and the customer may have
         * been emailed two different places to go.
         */
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/ready")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"collectionPoint":"Somewhere else"}""")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(serviceRequests.findAll().get(0).collectionPoint()).isEqualTo("Head office");
    }

    @Test
    @DisplayName("COLLECTING IT CLOSES IT — it leaves the staff queue")
    void collectedRequestsLeaveTheQueue() throws Exception {
        MockHttpSession customer = customerWithAccounts("done@example.rw", CURRENT_ONLY);
        String id = askForCard(customer, firstAccountIdOf(customer)).get("id").asString();

        mvc.perform(
                        asStaff(
                                ADMIN_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/ready")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"collectionPoint":"Head office"}""")))
                .andExpect(status().isOk());

        assertThat(staffQueue()).hasSize(1);

        mvc.perform(
                        asStaff(
                                ADMIN_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/collected")
                                        .with(csrf())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COLLECTED"));

        /* Otherwise the queue grows for ever and staff cannot tell what is outstanding. */
        assertThat(staffQueue()).isEmpty();

        /* It stays on the customer's own list, which is their record that they have it. */
        mvc.perform(get("/api/v1/service-requests").session(customer))
                .andExpect(jsonPath("$[0].status").value("COLLECTED"));
    }

    @Test
    @DisplayName("DECLINING NEEDS A REASON, and the customer is shown it")
    void decliningNeedsAReason() throws Exception {
        MockHttpSession customer = customerWithAccounts("refused@example.rw", CURRENT_ONLY);
        String id = askForCard(customer, firstAccountIdOf(customer)).get("id").asString();

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/decline")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"reason":"  "}""")))
                .andExpect(status().isBadRequest());

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/service-requests/" + id + "/decline")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"reason":"Your account has been dormant for over a year."}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        /* On their own list, not only in their inbox, so the two cannot disagree. */
        mvc.perform(get("/api/v1/service-requests").session(customer))
                .andExpect(jsonPath("$[0].declineReason")
                        .value("Your account has been dormant for over a year."));
    }

    /* =================================================== scope and the rules */

    @Test
    @DisplayName("A CUSTOMER CANNOT ASK FOR A CARD ON SOMEBODY ELSE'S ACCOUNT")
    void requestsAreScopedToTheirOwner() throws Exception {
        MockHttpSession mine = customerWithAccounts("owner.a@example.rw", CURRENT_ONLY);
        MockHttpSession theirs = customerWithAccounts("owner.b@example.rw", SAVINGS_ONLY);

        String theirAccount = firstAccountIdOf(theirs);

        /*
         * 404 rather than 403: a different answer would let anybody signed in confirm that
         * an account id belongs to somebody.
         */
        mvc.perform(
                        post("/api/v1/service-requests")
                                .session(mine)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"requestType":"CARD","cardType":"DEBIT",\
                                        "accountId":"%s"}"""
                                                .formatted(theirAccount)))
                .andExpect(status().isNotFound());

        assertThat(serviceRequests.findAll()).isEmpty();
    }

    @Test
    @DisplayName("A CUSTOMER CANNOT SEE ANOTHER CUSTOMER'S REQUESTS")
    void listsAreScopedToTheirOwner() throws Exception {
        MockHttpSession mine = customerWithAccounts("list.a@example.rw", CURRENT_ONLY);
        MockHttpSession theirs = customerWithAccounts("list.b@example.rw", SAVINGS_ONLY);

        askForCard(mine, firstAccountIdOf(mine));

        mvc.perform(get("/api/v1/service-requests").session(theirs))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("CHEQUE BOOKS ARE CURRENT-ACCOUNT ONLY, refused by the SERVER")
    void chequeBooksNeedACurrentAccount() throws Exception {
        MockHttpSession customer = customerWithAccounts("saver@example.rw", SAVINGS_ONLY);
        String savings = firstAccountIdOf(customer);

        /*
         * The form narrows its own list to current accounts, and a rule enforced only in a
         * dropdown is not a rule: this request can be built by anything that can make an
         * HTTP call.
         */
        mvc.perform(
                        post("/api/v1/service-requests")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"requestType":"CHEQUE_BOOK","leaves":50,\
                                        "accountId":"%s"}"""
                                                .formatted(savings)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("current account")));

        assertThat(serviceRequests.findAll()).isEmpty();
    }

    @Test
    @DisplayName("AN ODD NUMBER OF LEAVES IS REFUSED rather than rounded")
    void leavesAreValidated() throws Exception {
        MockHttpSession customer = customerWithAccounts("leaves@example.rw", CURRENT_ONLY);
        String account = firstAccountIdOf(customer);

        mvc.perform(
                        post("/api/v1/service-requests")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"requestType":"CHEQUE_BOOK","leaves":37,\
                                        "accountId":"%s"}"""
                                                .formatted(account)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(serviceRequests.findAll()).isEmpty();
    }

    @Test
    @DisplayName("ASKING TWICE IS REFUSED, and names the reference they already have")
    void noDuplicateOpenRequests() throws Exception {
        MockHttpSession customer = customerWithAccounts("twice@example.rw", CURRENT_ONLY);
        String account = firstAccountIdOf(customer);

        String reference = askForCard(customer, account).get("reference").asString();

        /*
         * Pressing the button twice should not make the bank print two cards — and the
         * refusal names the existing reference, because somebody pressing it again usually
         * did so after failing to find the first one.
         */
        mvc.perform(
                        post("/api/v1/service-requests")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"requestType":"CARD","cardType":"DEBIT",\
                                        "accountId":"%s"}"""
                                                .formatted(account)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString(reference)));

        assertThat(serviceRequests.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("A CUSTOMER CANNOT MARK THEIR OWN REQUEST READY")
    void customersCannotFulfilTheirOwn() throws Exception {
        MockHttpSession customer = customerWithAccounts("selfserve@example.rw", CURRENT_ONLY);
        String id = askForCard(customer, firstAccountIdOf(customer)).get("id").asString();

        mvc.perform(
                        post("/api/v1/admin/service-requests/" + id + "/ready")
                                .session(customer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"collectionPoint":"Wherever I like"}"""))
                .andExpect(status().isForbidden());

        assertThat(serviceRequests.findAll().get(0).status())
                .isEqualTo(ServiceRequestStatus.SUBMITTED);
    }
}
