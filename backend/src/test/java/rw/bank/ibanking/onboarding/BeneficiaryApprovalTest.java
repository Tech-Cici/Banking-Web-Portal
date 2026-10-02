package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import rw.bank.ibanking.onboarding.domain.BeneficiaryStatus;

/**
 * A SAVED PAYEE, AND THE STAFF CHECK THAT LETS IT BE PAID.
 *
 * <p>WHAT WAS WRONG BEFORE ANY OF THIS EXISTED, because it is what the tests are aimed at.
 * Payees lived only in the browser's mock: adding one produced a PENDING row that nothing
 * ever moved on, and the transfer screen's payee dropdown filtered on the payee's TYPE and
 * not its status — so an unchecked payee was selectable and payable on the one screen that
 * moves money, while the customer was told it was in a "cooling-off period" that did not
 * exist.
 *
 * <p>So the assertions worth having are not "the endpoint returns 201". They are the
 * properties that a plausible edit can quietly break:
 *
 * <ul>
 *   <li>A NEW PAYEE CANNOT BE PAID, refused by the SERVER rather than by a dropdown.
 *   <li>APPROVAL IS WHAT CHANGES THAT, and either staff role may give it.
 *   <li>THE REVIEWER IS SHOWN SOMETHING TO CHECK — the name the bank holds, beside the name
 *       the customer typed, with a verdict that tells a mismatch apart from a comparison
 *       that could not be made.
 *   <li>NO FULL ACCOUNT NUMBER IS EVER IN A RESPONSE, customer-facing or staff-facing.
 *   <li>A SECOND DECISION ON A SETTLED PAYEE IS REFUSED, so two reviewers cannot overwrite
 *       each other's audit trail.
 * </ul>
 */
@DisplayName("Saved payees")
class BeneficiaryApprovalTest extends OnboardingIntegrationTest {

    /** The payer. Holds 50,000 so a transfer attempt fails on the payee, not the balance. */
    private static final String PAYER_ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4007000000001","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"50000"}]}""";

    /** The payee's own account at this bank, so the holder name can be resolved. */
    private static final String PAYEE_ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4008000000002","accountType":"SAVINGS",\
            "currency":"RWF","openingBalance":"1000"}]}""";

    private static final String PAYEE_NUMBER = "4008000000002";

    /**
     * A SECOND payer, with its own account number.
     *
     * <p>Account numbers are unique across customers and the service refuses a reuse with
     * a 422, so a test needing two payers cannot give them both {@link #PAYER_ACCOUNTS} —
     * which is how the first draft of the ownership test failed, in the setup rather than
     * in the thing it was checking.
     */
    private static final String OTHER_PAYER_ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4007000000003","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"50000"}]}""";

    /* ------------------------------------------------------------- helpers */

    /** Saves a payee for this session and returns the created row's id. */
    private String addPayee(MockHttpSession session, String name, String destination)
            throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/beneficiaries")
                                        .session(session)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"name":"%s","beneficiaryType":"INTERNAL",\
                                                "provider":"Zigama CSS","destination":"%s",\
                                                "currency":"RWF"}"""
                                                        .formatted(name, destination)))
                        .andExpect(status().isCreated())
                        /* Unpayable from the moment it exists. There is no other first state. */
                        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return json.readTree(body).get("id").asString();
    }

    /** The staff queue as `user` sees it. */
    private JsonNode queueAs(String user) throws Exception {
        String body =
                mvc.perform(asStaff(user, get("/api/v1/admin/beneficiaries")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body);
    }

    /** Attempts a transfer to a saved payee and returns the raw result for assertion. */
    private org.springframework.test.web.servlet.ResultActions payTo(
            MockHttpSession session, String payeeId, String sourceAccountId) throws Exception {

        return mvc.perform(
                post("/api/v1/transfers")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", "pay-" + payeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"sourceAccountId":"%s","beneficiaryId":"%s",\
                                "amount":"500","reference":"Rent"}"""
                                        .formatted(sourceAccountId, payeeId)));
    }

    /** The payer's own account id, which is all the client ever has. */
    private String firstAccountIdOf(MockHttpSession session) throws Exception {
        String body =
                mvc.perform(get("/api/v1/accounts").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).get(0).get("id").asString();
    }

    /* ======================================================== the gate */

    @Test
    @DisplayName("A NEW PAYEE CANNOT BE PAID, and it is the server that refuses")
    void aNewPayeeCannotBePaid() throws Exception {
        customerWithAccounts("payee.holder@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer@example.rw", PAYER_ACCOUNTS);

        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);
        String source = firstAccountIdOf(payer);

        /*
         * THIS IS THE REGRESSION THAT MATTERS. The old arrangement filtered the payee list
         * in the browser, so this request — which a browser would not normally build —
         * went straight through. A control a client applies is not a control.
         */
        payTo(payer, payeeId, source)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("still being checked")));

        /* And no money moved. */
        assertThat(transfers.findAll()).isEmpty();
    }

    @Test
    @DisplayName("ONCE APPROVED IT CAN BE PAID, and an ADMIN may approve it")
    void approvalIsWhatMakesItPayable() throws Exception {
        customerWithAccounts("holder2@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer2@example.rw", PAYER_ACCOUNTS);

        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);
        String source = firstAccountIdOf(payer);

        /*
         * AN ADMIN, deliberately — not a manager. The bank asked for either staff role to
         * be able to clear a payee, and the four-eyes rule that keeps ADMIN and MANAGER
         * disjoint is about creating and releasing an ACCOUNT: here the maker is the
         * customer, so the separation holds whoever checks it.
         */
        mvc.perform(asStaff(ADMIN_EMAIL, post("/api/v1/admin/beneficiaries/" + payeeId + "/approve").with(csrf())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        payTo(payer, payeeId, source).andExpect(status().isOk());
        assertThat(transfers.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("A MANAGER MAY APPROVE ONE TOO")
    void managersMayAlsoApprove() throws Exception {
        customerWithAccounts("holder3@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer3@example.rw", PAYER_ACCOUNTS);
        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        mvc.perform(asStaff(MANAGER_EMAIL, post("/api/v1/admin/beneficiaries/" + payeeId + "/approve").with(csrf())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("A CUSTOMER CANNOT APPROVE THEIR OWN PAYEE")
    void customersCannotApproveTheirOwn() throws Exception {
        customerWithAccounts("holder4@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer4@example.rw", PAYER_ACCOUNTS);
        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        /*
         * The whole control would be worth nothing if the person who adds a payee could
         * clear it. A customer session on the staff endpoint is not a manager.
         */
        mvc.perform(
                        post("/api/v1/admin/beneficiaries/" + payeeId + "/approve")
                                .session(payer)
                                .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(beneficiaries.findAll().get(0).status())
                .isEqualTo(BeneficiaryStatus.PENDING_VERIFICATION);
    }

    @Test
    @DisplayName("A REFUSED PAYEE CANNOT BE PAID EITHER, and the customer is told why")
    void aRefusedPayeeCannotBePaid() throws Exception {
        customerWithAccounts("holder5@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer5@example.rw", PAYER_ACCOUNTS);
        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);
        String source = firstAccountIdOf(payer);

        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/beneficiaries/" + payeeId + "/refuse")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"reason":"The name does not match this account."}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUSED"));

        payTo(payer, payeeId, source).andExpect(status().isUnprocessableEntity());

        /* The reason reaches the customer's own list, so they can put it right themselves. */
        mvc.perform(get("/api/v1/beneficiaries").session(payer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].refusedReason").value("The name does not match this account."));
    }

    @Test
    @DisplayName("A BLANK REFUSAL REASON IS REFUSED — \"the bank said no\" guarantees a phone call")
    void aRefusalNeedsAReason() throws Exception {
        customerWithAccounts("holder6@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer6@example.rw", PAYER_ACCOUNTS);
        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        /*
         * 400 rather than 422: @NotBlank on the request record fires at the controller
         * boundary, before the service is reached. The entity's own guard is the second
         * line, for any future caller that does not come through this endpoint.
         */
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/beneficiaries/" + payeeId + "/refuse")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"reason":"   "}""")))
                .andExpect(status().isBadRequest());

        assertThat(beneficiaries.findAll().get(0).status())
                .isEqualTo(BeneficiaryStatus.PENDING_VERIFICATION);
    }

    /* =================================================== what the reviewer sees */

    @Test
    @DisplayName("THE QUEUE SHOWS THE NAME THE BANK HOLDS, so the reviewer has something to check")
    void theQueueCarriesTheHeldName() throws Exception {
        /* The holder's real name comes from the registration, which uses a fixed name. */
        customerWithAccounts("holder7@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer7@example.rw", PAYER_ACCOUNTS);

        String realName = customers.findByEmailIgnoreCase("holder7@example.rw").orElseThrow().fullName();
        addPayee(payer, realName, PAYEE_NUMBER);

        JsonNode queue = queueAs(ADMIN_EMAIL);
        assertThat(queue).hasSize(1);

        JsonNode row = queue.get(0);
        assertThat(row.get("heldName").asString()).isEqualTo(realName);
        /* Typed exactly what the bank holds, so the verdict is a match rather than a guess. */
        assertThat(row.get("nameCheck").asString()).isEqualTo("MATCH");
    }

    @Test
    @DisplayName("A WRONG NAME IS A MISMATCH, not a blank the reviewer reads as a pass")
    void aWrongNameIsAMismatch() throws Exception {
        customerWithAccounts("holder8@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer8@example.rw", PAYER_ACCOUNTS);

        addPayee(payer, "Somebody Else Entirely", PAYEE_NUMBER);

        JsonNode row = queueAs(MANAGER_EMAIL).get(0);
        assertThat(row.get("nameCheck").asString()).isEqualTo("MISMATCH");
        /* And the real holder is still shown, which is what makes the mismatch actionable. */
        assertThat(row.has("heldName")).isTrue();
        assertThat(row.get("heldName").asString()).isNotBlank();
    }

    @Test
    @DisplayName("A PAYEE AT ANOTHER BANK IS UNAVAILABLE, which is not the same as a mismatch")
    void anotherBanksPayeeCannotBeChecked() throws Exception {
        MockHttpSession payer = customerWithAccounts("payer9@example.rw", PAYER_ACCOUNTS);

        mvc.perform(
                        post("/api/v1/beneficiaries")
                                .session(payer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"Someone At Another Bank",\
                                        "beneficiaryType":"DOMESTIC","provider":"Bank of Kigali",\
                                        "destination":"1234567890123","currency":"RWF"}"""))
                .andExpect(status().isCreated());

        JsonNode row = queueAs(ADMIN_EMAIL).get(0);
        /*
         * THE DISTINCTION IS THE TEST. This service cannot ask another bank who holds an
         * account, so there is no name and no comparison. Reporting that as a mismatch
         * would cry wolf on every external payee; reporting it as a match would be a lie.
         */
        assertThat(row.get("nameCheck").asString()).isEqualTo("UNAVAILABLE");
        /*
         * ABSENT, not null. The service sets `default-property-inclusion: non_null`, so a
         * null field is left out of the JSON entirely — which is why the client's type for
         * this field has to be optional rather than nullable, and why asserting
         * `get(...).isNull()` here threw instead of failing.
         */
        assertThat(row.has("heldName")).isFalse();
    }

    /* ======================================================== the rules */

    @Test
    @DisplayName("NO RESPONSE CARRIES THE FULL ACCOUNT NUMBER, customer's or staff's")
    void noResponseLeaksTheNumber() throws Exception {
        customerWithAccounts("holder10@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer10@example.rw", PAYER_ACCOUNTS);
        addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        String mine =
                mvc.perform(get("/api/v1/beneficiaries").session(payer))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String staffQueue =
                mvc.perform(asStaff(ADMIN_EMAIL, get("/api/v1/admin/beneficiaries")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        /*
         * The mask is the only form of the destination that travels. A staff screen is not
         * an exception: it is a screen in an open-plan office.
         */
        assertThat(mine).doesNotContain(PAYEE_NUMBER).contains("**** 0002");
        assertThat(staffQueue).doesNotContain(PAYEE_NUMBER).contains("**** 0002");
    }

    @Test
    @DisplayName("A SECOND DECISION IS REFUSED, so two reviewers cannot overwrite each other")
    void onlyOneDecisionPerPayee() throws Exception {
        customerWithAccounts("holder11@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer11@example.rw", PAYER_ACCOUNTS);
        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        mvc.perform(asStaff(ADMIN_EMAIL, post("/api/v1/admin/beneficiaries/" + payeeId + "/approve").with(csrf())))
                .andExpect(status().isOk());

        /*
         * The second reviewer gets a conflict rather than silently replacing the first
         * one's name on the audit row — or, in the other order, making a payee the
         * customer has already been emailed about unpayable again.
         */
        mvc.perform(
                        asStaff(
                                MANAGER_EMAIL,
                                post("/api/v1/admin/beneficiaries/" + payeeId + "/refuse")
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"reason":"Changed my mind."}""")))
                .andExpect(status().isUnprocessableEntity());

        assertThat(beneficiaries.findAll().get(0).status()).isEqualTo(BeneficiaryStatus.ACTIVE);
        assertThat(beneficiaries.findAll().get(0).reviewedByName()).isNotBlank();
    }

    @Test
    @DisplayName("A PAYEE IS ONE CUSTOMER'S OWN — another customer cannot see or pay it")
    void payeesAreScopedToTheirOwner() throws Exception {
        customerWithAccounts("holder12@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession mine = customerWithAccounts("owner12@example.rw", PAYER_ACCOUNTS);
        MockHttpSession theirs =
                customerWithAccounts("stranger12@example.rw", OTHER_PAYER_ACCOUNTS);

        String payeeId = addPayee(mine, "Teta Eliana", PAYEE_NUMBER);

        mvc.perform(asStaff(ADMIN_EMAIL, post("/api/v1/admin/beneficiaries/" + payeeId + "/approve").with(csrf())))
                .andExpect(status().isOk());

        /* Not on the stranger's list. */
        mvc.perform(get("/api/v1/beneficiaries").session(theirs))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        /*
         * And not payable by them, even though it IS approved — approval is not a licence
         * for everybody. 404 rather than 403: a different answer would let anybody signed
         * in confirm that a payee id belongs to somebody.
         */
        payTo(theirs, payeeId, firstAccountIdOf(theirs)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A CUSTOMER CANNOT SAVE THEIR OWN ACCOUNT AS A PAYEE")
    void ownAccountsAreNotPayees() throws Exception {
        MockHttpSession payer = customerWithAccounts("payer13@example.rw", PAYER_ACCOUNTS);

        mvc.perform(
                        post("/api/v1/beneficiaries")
                                .session(payer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"Me","beneficiaryType":"INTERNAL",\
                                        "provider":"Zigama CSS","destination":"4007000000001",\
                                        "currency":"RWF"}"""))
                .andExpect(status().isUnprocessableEntity());

        /* Otherwise the customer's own account would sit in a staff queue to be approved. */
        assertThat(beneficiaries.findAll()).isEmpty();
    }

    @Test
    @DisplayName("THE SAME DESTINATION TWICE IS REFUSED while one is still live")
    void noDuplicateDestinations() throws Exception {
        customerWithAccounts("holder14@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer14@example.rw", PAYER_ACCOUNTS);

        addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        /*
         * Two rows for one destination mean two reviews of one decision, which can
         * disagree — and a dropdown showing the destination twice gives the customer no
         * way to tell which entry is the usable one.
         */
        mvc.perform(
                        post("/api/v1/beneficiaries")
                                .session(payer)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"Teta E","beneficiaryType":"INTERNAL",\
                                        "provider":"Zigama CSS","destination":"%s",\
                                        "currency":"RWF"}"""
                                                .formatted(PAYEE_NUMBER)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(beneficiaries.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("APPROVING EMAILS THE CUSTOMER, and the message carries no account number")
    void approvalIsEmailed() throws Exception {
        customerWithAccounts("holder15@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer15@example.rw", PAYER_ACCOUNTS);
        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        mvc.perform(asStaff(ADMIN_EMAIL, post("/api/v1/admin/beneficiaries/" + payeeId + "/approve").with(csrf())))
                .andExpect(status().isOk());

        var mail = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc("payer15@example.rw");
        assertThat(mail).isNotEmpty();

        String body = mail.get(0).body();
        /*
         * The wait is only tolerable because it ends with a message. And the message is the
         * bank's one chance to tell somebody a payee appeared that they did not add, which
         * is the clearest sign another person has their password.
         */
        assertThat(body).contains("Teta Eliana").contains("**** 0002");
        assertThat(body).doesNotContain(PAYEE_NUMBER);
        assertThat(body).containsIgnoringCase("if you did not add this payee");
    }

    @Test
    @DisplayName("A CUSTOMER MAY REMOVE A PAYEE THAT IS STILL WAITING")
    void waitingPayeesCanBeRemoved() throws Exception {
        customerWithAccounts("holder16@example.rw", PAYEE_ACCOUNTS);
        MockHttpSession payer = customerWithAccounts("payer16@example.rw", PAYER_ACCOUNTS);
        String payeeId = addPayee(payer, "Teta Eliana", PAYEE_NUMBER);

        /* Somebody who realises they typed the wrong number should not have to wait to be
           refused before they can take it off their own list. */
        mvc.perform(delete("/api/v1/beneficiaries/" + payeeId).session(payer).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(beneficiaries.findAll()).isEmpty();
        assertThat(queueAs(ADMIN_EMAIL)).isEmpty();
    }
}
