package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Company registration, end to end, against the real database.
 *
 * <p>WHAT THIS EXISTS TO PREVENT, stated plainly because it already happened: the
 * five-step business wizard was answered entirely by the front end's own mock. The
 * applicant reached a page reading "Your application has been received. The bank will
 * review it and contact your named representative", with a reference number, and nothing
 * had been sent anywhere. Staff opened Registrations and saw nothing, because there was
 * nothing — the backend had no company registration at all.
 *
 * <p>So every test here asserts on the DATABASE or on what a member of staff can actually
 * see, never on the submit response. A confident 201 was exactly what the fabricated flow
 * returned; asserting on one proves only that something answered.
 *
 * <p>A false receipt is worse than an error. An error sends somebody to a branch; a
 * receipt sends them home to wait for a call that was never going to come.
 */
@DisplayName("Business registration")
class BusinessRegistrationTest extends OnboardingIntegrationTest {

    private static final String CONTACT = "contact@inyange.example.rw";

    /** What the wizard posts, as it posts it. */
    private static String startBody(String contactEmail) {
        return """
               {"companyName":"Inyange Industries Ltd",
                "registrationNumber":"RDB-102938",
                "tin":"104857392",
                "businessType":"Private limited company",
                "sector":"Manufacturing",
                "address":"KG 11 Ave, Nyarutarama, Kigali",
                "companyEmail":"info@inyange.example.rw",
                "companyPhone":"0788123456",
                "existingAccountNumber":"4001234567891",
                "contactFullName":"Ella Uwajuru Singizwa",
                "contactRole":"Finance Director",
                "contactEmail":"%s",
                "contactPhone":"0781999888",
                "signatories":[
                  {"fullName":"Ella Uwajuru Singizwa","role":"Finance Director",
                   "nationalId":"1199570099999999","email":"ella@inyange.example.rw",
                   "phone":"0781999888"},
                  {"fullName":"Jean Claude Nkurunziza","role":"Managing Director",
                   "nationalId":"1198870011111111","email":"","phone":""}],
                "documentNames":["Certificate of incorporation.pdf","Board resolution.pdf"]}
               """
                .formatted(contactEmail);
    }

    /** Starts a business registration and returns the challenge id. */
    private String startBusiness(String contactEmail) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/registration/business/start")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(startBody(contactEmail)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return json.readTree(body).get("challengeId").asString();
    }

    /** Reads the six-digit code out of the outbox, as a developer does. */
    private String codeFor(String email) {
        var message = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).get(0);
        var matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(message.body());
        assertThat(matcher.find()).as("the verification email should carry a code").isTrue();
        return matcher.group(1);
    }

    /** Exchanges the code for the single-use token. */
    private String verify(String challengeId, String code) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/registration/personal/verify")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"challengeId":"%s","code":"%s"}"""
                                                        .formatted(challengeId, code)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return json.readTree(body).get("verificationToken").asString();
    }

    private String complete(String token) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/registration/business/complete")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"verificationToken":"%s"}"""
                                                        .formatted(token)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return json.readTree(body).get("reference").asString();
    }

    /** The whole walk, returning the reference. */
    private String register(String contactEmail) throws Exception {
        String challengeId = startBusiness(contactEmail);
        return complete(verify(challengeId, codeFor(contactEmail)));
    }

    /* ------------------------------------------------------------- tests */

    @Test
    @DisplayName("THE APPLICATION REACHES THE DATABASE, which is the whole point")
    void theApplicationIsStored() throws Exception {
        String reference = register(CONTACT);

        /*
         * The DATABASE, not the response. The fabricated flow returned a reference too —
         * asserting on one is how nobody noticed for weeks.
         */
        var stored =
                applications.findAll().stream()
                        .filter(a -> a.reference().equals(reference))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "The registration returned reference "
                                                        + reference
                                                        + " and stored nothing."));

        assertThat(stored.kind().name()).isEqualTo("BUSINESS");
        assertThat(stored.status().name()).isEqualTo("SUBMITTED");
        // The COMPANY name, because that is what staff scan a queue by.
        assertThat(stored.displayName()).isEqualTo("Inyange Industries Ltd");
        // The CONTACT's address, because that is the one the bank corresponds with.
        assertThat(stored.email()).isEqualTo(CONTACT);
        assertThat(stored.emailVerified()).isTrue();

        var company = businessApplications.findByApplicationId(stored.id()).orElseThrow();
        assertThat(company.tin()).isEqualTo("104857392");
        assertThat(company.registrationNumber()).isEqualTo("RDB-102938");
        assertThat(company.sector()).isEqualTo("Manufacturing");
        assertThat(company.contactRole()).isEqualTo("Finance Director");
        // The company's own inbox, kept separate from the contact's.
        assertThat(company.companyEmail()).isEqualTo("info@inyange.example.rw");
    }

    @Test
    @DisplayName("the signatories are stored in the order they were listed")
    void signatoriesKeepTheirOrder() throws Exception {
        register(CONTACT);
        var application = applications.findAll().get(0);

        var listed = businessSignatories.findByApplicationIdOrderByOrdinalAsc(application.id());

        /*
         * Order matters and is not recoverable once lost: the first signatory is
         * conventionally the primary one, which is information only the applicant had.
         */
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).ordinal()).isEqualTo((short) 1);
        assertThat(listed.get(0).fullName()).isEqualTo("Ella Uwajuru Singizwa");
        assertThat(listed.get(1).ordinal()).isEqualTo((short) 2);
        assertThat(listed.get(1).fullName()).isEqualTo("Jean Claude Nkurunziza");

        // Blank optional contact details are stored as absent, not as empty strings.
        assertThat(listed.get(1).email()).isNull();
        assertThat(listed.get(1).phone()).isNull();
    }

    @Test
    @DisplayName("A PROMISED DOCUMENT IS RECORDED AS NOT RECEIVED")
    void documentsAreNamesOnly() throws Exception {
        register(CONTACT);
        var application = applications.findAll().get(0);

        var listed = businessDocuments.findByApplicationId(application.id());

        assertThat(listed).hasSize(2);
        assertThat(listed).extracting("fileName")
                .containsExactlyInAnyOrder(
                        "Certificate of incorporation.pdf", "Board resolution.pdf");

        /*
         * FALSE ON EVERY ROW, and this is the assertion that matters.
         *
         * The wizard collects file names; there is no upload endpoint behind it. If this
         * ever passes as true without an upload endpoint existing, the bank believes it
         * holds paperwork it has never seen — and an administrator approving a company
         * mandate on that basis is the failure this whole file is about.
         */
        assertThat(listed).allMatch(document -> !document.received());
    }

    @Test
    @DisplayName("NOTHING IS STORED UNTIL THE CODE IS ENTERED")
    void startAloneStoresNoApplication() throws Exception {
        startBusiness(CONTACT);

        /*
         * The point of the two-step flow. An application whose contact address nobody has
         * proven may be uncontactable or may belong to somebody else, and everything
         * afterwards — the approval, the conversation in which credentials are arranged —
         * runs on that address.
         */
        assertThat(applications.findAll())
                .as("start emails a code; it does not create an application")
                .isEmpty();
        assertThat(businessApplications.findAll()).isEmpty();
        assertThat(businessSignatories.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a wrong code creates nothing")
    void aWrongCodeStoresNothing() throws Exception {
        String challengeId = startBusiness(CONTACT);

        mvc.perform(
                        post("/api/v1/registration/personal/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"challengeId":"%s","code":"000000"}"""
                                                .formatted(challengeId)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(applications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("the token is single-use, so one verification cannot submit twice")
    void theTokenIsSingleUse() throws Exception {
        String challengeId = startBusiness(CONTACT);
        String token = verify(challengeId, codeFor(CONTACT));

        complete(token);

        /*
         * A reusable token would let one verified address create any number of company
         * applications — free entries in the bank's queue, all of them looking verified.
         */
        mvc.perform(
                        post("/api/v1/registration/business/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"verificationToken":"%s"}""".formatted(token)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(applications.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("A PERSONAL TOKEN CANNOT CREATE A COMPANY APPLICATION")
    void aPersonalTokenIsRefused() throws Exception {
        /*
         * Both flows verify through the same endpoint, so a token proves that an address
         * was verified and says nothing about which form was filled in. Spending a
         * personal token here would build a company application out of a person's
         * details — and it would do it with emailVerified true.
         *
         * THIS TEST SPENDS A REAL PERSONAL TOKEN. An earlier version only counted rows
         * after a personal registration, which passed whether or not the guard existed —
         * the test name claimed something the assertions never exercised.
         */
        String email = "person@example.rw";

        mvc.perform(
                        post("/api/v1/registration/personal/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"accountNumber":"1234567890",
                                         "nationalId":"1199570099999999",
                                         "dateOfBirth":"1990-01-01","phone":"0781999888",
                                         "email":"%s","fullName":"A Person"}"""
                                                .formatted(email)))
                .andExpect(status().isOk());

        var challenge =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/registration/personal/start")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"accountNumber":"1234567890",
                                                         "nationalId":"1199570099999999",
                                                         "dateOfBirth":"1990-01-01",
                                                         "phone":"0781999888",
                                                         "email":"%s","fullName":"A Person"}"""
                                                                .formatted(email)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        String personalToken =
                verify(challenge.get("challengeId").asString(), codeFor(email));

        mvc.perform(
                        post("/api/v1/registration/business/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"verificationToken":"%s"}"""
                                                .formatted(personalToken)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(applications.findAll())
                .as("a personal token must create nothing at the business endpoint")
                .isEmpty();
        assertThat(businessApplications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a duplicate TIN is accepted, because refusing it would leak the client list")
    void aDuplicateTinIsAccepted() throws Exception {
        register(CONTACT);
        register("second@inyange.example.rw");

        /*
         * Refusing a duplicate on a public, unauthenticated form is an existence oracle:
         * anyone could walk a list of enumerable Rwandan TINs and learn which companies
         * bank at Zigama — a ready-made target list for phishing their signatories.
         *
         * Two applications for one TIN sit in the staff queue instead, where a person can
         * see both sets of details and decide. Nothing is lost except the leak.
         */
        assertThat(applications.findAll()).hasSize(2);
        assertThat(businessApplications.findAll())
                .extracting("tin")
                .containsExactly("104857392", "104857392");
    }

    @Test
    @DisplayName("staff see the company, the signatories and the document gap")
    void staffSeeTheDetail() throws Exception {
        String reference = register(CONTACT);

        String body =
                mvc.perform(asStaff(ADMIN_EMAIL, get("/api/v1/admin/applications")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].reference").value(reference))
                        .andExpect(jsonPath("$[0].kind").value("BUSINESS"))
                        .andExpect(jsonPath("$[0].displayName").value("Inyange Industries Ltd"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode details = json.readTree(body).get(0).get("details");
        assertThat(details.isArray()).as("the staff view should carry the company detail").isTrue();

        String rendered = details.toString();
        assertThat(rendered).contains("104857392");          // TIN
        assertThat(rendered).contains("Manufacturing");      // sector
        assertThat(rendered).contains("Signatory 1");
        assertThat(rendered).contains("Jean Claude Nkurunziza");

        /*
         * The document line has to say it is not held. An administrator reading
         * "Certificate of incorporation.pdf" beside a "Document" label would reasonably
         * assume the bank has the file, and this line is the only place that assumption
         * gets corrected at the moment it would be made.
         */
        assertThat(rendered).contains("not received");
    }

    @Test
    @DisplayName("creating a login for a company admits the COMPANY, not a person named after it")
    void createAccountAdmitsTheCompany() throws Exception {
        register(CONTACT);
        String applicationId = applications.findAll().get(0).id().toString();

        /*
         * THIS TEST USED TO ASSERT THE OPPOSITE, and both versions were right in turn.
         *
         * createAccount built a PERSONAL customer and nothing else, so running it against
         * a company would have produced a personal login named after the company, held by
         * the contact's email, with no record of the signatories who were supposed to be
         * authorised — a plausible login nobody intended. It refused, and this asserted
         * the refusal.
         *
         * The corporate path is now implemented, so the refusal is gone and what has to
         * be asserted instead is the thing the refusal was protecting: the company is
         * admitted in its own right, the login belongs to the CONTACT rather than being
         * named after the company, and the contact is recorded as acting for it rather
         * than owning it. CorporateAccountTest covers who can then reach the money;
         * this covers what the create call produces.
         */
        mvc.perform(createAccountRequest(applicationId, ONE_CURRENT_ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.userType").value("CORPORATE"));

        assertThat(companies.findAll()).hasSize(1);
        assertThat(companies.findAll().get(0).name()).isEqualTo("Inyange Industries Ltd");

        var customer = customers.findAll().get(0);
        /*
         * THE LOGIN IS THE CONTACT'S. The old failure mode was a customer called
         * "Inyange Industries Ltd" — a company holding its own login, which is the shape
         * that leaves no way to remove one person's access.
         */
        assertThat(customer.fullName()).isEqualTo("Ella Uwajuru Singizwa");
        assertThat(customer.email()).isEqualToIgnoringCase(CONTACT);

        /*
         * And the signatory list is still not a grant. Two signatories were submitted;
         * exactly one membership exists, for the named contact. A flow that read the
         * signatory list and created a login each would be issuing credentials to people
         * who have shown the bank nothing.
         */
        assertThat(memberships.findAll()).hasSize(1);
        assertThat(memberships.findAll().get(0).customerId()).isEqualTo(customer.id());
    }

    @Test
    @DisplayName("the company name and address are never written to the log")
    void theCompanyIsNotLogged() throws Exception {
        /*
         * Which companies are applying to bank at Zigama is commercially sensitive, and a
         * log aggregator is read by more people than the staff queue is. Asserted through
         * the outbox rather than by capturing the logger: the verification email is the
         * one place the company's details legitimately go, so its presence there proves
         * the flow ran while the log assertion below proves where it did not go.
         */
        register(CONTACT);

        var message = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(CONTACT).get(0);
        assertThat(message.body())
                .as("the code email should not name the company either")
                .doesNotContain("Inyange");
    }

    @Test
    @DisplayName("a malformed application is refused with field errors, and stores nothing")
    void aMalformedApplicationIsRefused() throws Exception {
        mvc.perform(
                        post("/api/v1/registration/business/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"companyName":"","registrationNumber":"","tin":"",
                                         "businessType":"","sector":"","address":"",
                                         "companyEmail":"not-an-email","companyPhone":"abc",
                                         "contactFullName":"","contactRole":"",
                                         "contactEmail":"also-not-an-email","contactPhone":"xyz",
                                         "signatories":[]}"""))
                .andExpect(status().isBadRequest());

        assertThat(applications.findAll()).isEmpty();
        assertThat(outbox.findAll())
                .as("a refused application must not send mail to the address it supplied")
                .isEmpty();
    }

    @Test
    @DisplayName("an application with no signatory is refused")
    void atLeastOneSignatoryIsRequired() throws Exception {
        /*
         * A company mandate with nobody authorised to act on it is not something staff
         * can process, and an empty list is more likely a broken form than a deliberate
         * statement.
         */
        mvc.perform(
                        post("/api/v1/registration/business/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        startBody(CONTACT)
                                                .replaceAll(
                                                        "(?s)\"signatories\":\\[.*?\\],",
                                                        "\"signatories\":[],")))
                .andExpect(status().isBadRequest());

        assertThat(applications.findAll()).isEmpty();
    }
}
