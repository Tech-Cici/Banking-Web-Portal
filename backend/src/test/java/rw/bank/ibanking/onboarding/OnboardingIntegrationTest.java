package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import rw.bank.ibanking.onboarding.repo.AccountTransactionRepository;
import rw.bank.ibanking.onboarding.repo.ApplicationRepository;
import rw.bank.ibanking.onboarding.repo.BusinessApplicationRepository;
import rw.bank.ibanking.onboarding.repo.BusinessDocumentRepository;
import rw.bank.ibanking.onboarding.repo.BusinessSignatoryRepository;
import rw.bank.ibanking.onboarding.repo.CompanyRepository;
import rw.bank.ibanking.onboarding.repo.CorporateMembershipRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;
import rw.bank.ibanking.onboarding.repo.CustomerRepository;
import rw.bank.ibanking.onboarding.repo.EmailVerificationRepository;
import rw.bank.ibanking.onboarding.repo.LoginChallengeRepository;
import rw.bank.ibanking.onboarding.repo.OutboxRepository;
import rw.bank.ibanking.onboarding.repo.PasswordResetRequestRepository;
import rw.bank.ibanking.onboarding.repo.SignInRepository;
import rw.bank.ibanking.onboarding.repo.TransferRepository;
import rw.bank.ibanking.onboarding.repo.TrustedDeviceRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared setup for the onboarding and sign-in integration tests.
 *
 * <p>This exists for one reason: the order rows are deleted in. The test classes share a
 * Spring context and therefore one database, so each has to start from empty — and
 * `customers` is referenced by `login_challenges` and `sign_ins`, so deleting customers
 * first is a referential integrity violation rather than a clean slate.
 *
 * <p>That is exactly what happened. Adding the sign-in tests broke all eleven staff tests,
 * because those cleared `customers` while challenge rows still pointed at them, and whether
 * it broke depended on which class ran first. Keeping the order in one place means the next
 * table that references `customers` is handled by editing {@link #reset()} once, instead of
 * every test class discovering the constraint again through a failure that looks unrelated
 * to the change that caused it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class OnboardingIntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    @Autowired protected AccountTransactionRepository accountTransactions;
    @Autowired protected CustomerAccountRepository customerAccounts;
    @Autowired protected ApplicationRepository applications;
    @Autowired protected BusinessApplicationRepository businessApplications;
    @Autowired protected BusinessSignatoryRepository businessSignatories;
    @Autowired protected BusinessDocumentRepository businessDocuments;
    @Autowired protected CompanyRepository companies;
    @Autowired protected TransferRepository transfers;
    /*
     * The beneficiary-lookup limiter counts in memory, so it survives the database being
     * wiped between tests. Left alone, a test that makes twenty lookups fails the NEXT
     * test's first one — and which test that is depends on the order they happen to run in.
     */
    @Autowired protected rw.bank.ibanking.onboarding.service.BeneficiaryLookups lookups;
    @Autowired protected CorporateMembershipRepository memberships;
    @Autowired protected CustomerRepository customers;
    @Autowired protected EmailVerificationRepository verifications;
    @Autowired protected LoginChallengeRepository challenges;
    @Autowired protected SignInRepository signIns;
    @Autowired protected OutboxRepository outbox;
    @Autowired protected PasswordResetRequestRepository passwordRequests;
    @Autowired protected rw.bank.ibanking.onboarding.repo.BeneficiaryRepository beneficiaries;
    @Autowired protected rw.bank.ibanking.onboarding.repo.ServiceRequestRepository serviceRequests;
    @Autowired protected TrustedDeviceRepository trustedDevices;

    /**
     * The body every create-account call now needs.
     *
     * <p>In one place because the endpoint used to take no body at all, and five test
     * classes posted nothing. If the shape changes again, it should break here once
     * rather than in five files that each look like an unrelated failure.
     */
    protected static final String ONE_CURRENT_ACCOUNT =
            """
            {"accounts":[{"accountNumber":"4001111111111","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"0"}]}""";

    /**
     * The same account, opened with money in it.
     *
     * <p>Separate rather than a parameter, because most tests care only that an account
     * exists and a shared helper that took an amount would have every one of them
     * asserting on a figure they do not use.
     */
    protected static final String ONE_CURRENT_ACCOUNT_WITH_50000 =
            """
            {"accounts":[{"accountNumber":"4001111111111","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"50000"}]}""";

    /** Children first, parents after. */
    @BeforeEach
    void reset() {
        challenges.deleteAll();
        signIns.deleteAll();
        // Before customers: the FK cascades in PostgreSQL, but the tests run on H2
        // and an explicit order is one less dialect difference to be surprised by.
        // Transfers point at both accounts AND the ledger entries they produced, so
        // they go before either. Nothing here relies on a cascade.
        lookups.reset();
        /*
         * Password requests and trusted devices both point at `customers`, so both go
         * before it. Added here rather than in the one test class that creates them,
         * because the failure a missing delete produces lands in whichever OTHER class
         * happens to run next and looks nothing like the change that caused it — which is
         * the whole reason this method exists. Saved payees point at `customers` too and
         * join them for exactly the same reason.
         */
        passwordRequests.deleteAll();
        trustedDevices.deleteAll();
        beneficiaries.deleteAll();
        /* Points at customers AND customer_accounts, so it goes before both. */
        serviceRequests.deleteAll();
        transfers.deleteAll();
        accountTransactions.deleteAll();
        // Memberships point at customers AND companies; accounts point at companies. So
        // the companies can only go once both have, and nothing here relies on a cascade.
        memberships.deleteAll();
        customerAccounts.deleteAll();
        customers.deleteAll();
        companies.deleteAll();
        // A business application's children before the application row itself.
        businessDocuments.deleteAll();
        businessSignatories.deleteAll();
        businessApplications.deleteAll();
        applications.deleteAll();
        verifications.deleteAll();
        outbox.deleteAll();
    }

    /* ----------------------------------------------- the flow, in one place */

    /*
     * WALKING THE WHOLE FLOW IS THE POINT, so it is written once.
     *
     * Registering, creating the login, approving it and signing in were copied into each
     * test class that needed them, and the copies drifted: one of them carried the session
     * forward after the password change and got a 401 that looked like a bug in the
     * endpoint under test. Every step here is a real HTTP call with real credentials,
     * because each of these steps was individually green at a point when the thing the
     * bank wanted did not work end to end.
     */

    protected static final String ADMIN_EMAIL = "admin@zigama.local";
    protected static final String MANAGER_EMAIL = "manager@zigama.local";
    protected static final String STAFF_PASSWORD_VALUE = "ZigamaStaff1";

    /** The same request, signed in as a member of staff. */
    protected MockHttpServletRequestBuilder asStaff(
            String user, MockHttpServletRequestBuilder request) {
        return request.with(httpBasic(user, STAFF_PASSWORD_VALUE));
    }

    /** Registers somebody, verifies the code from the outbox, and returns the application id. */
    protected String registerApplicant(String email) throws Exception {
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
        var matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(message.body());
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

    /**
     * Registers a COMPANY and returns the application id.
     *
     * <p>The same walk as {@link #registerApplicant} for the other kind of applicant, and
     * here rather than in the test that uses it because more than one now does. It goes
     * through the real endpoints — start, read the code out of the outbox, verify,
     * complete — because a company application that was minted directly into the
     * repository would not prove that the flow a company actually uses produces one.
     *
     * @param companyName what staff see in the queue.
     * @param contactEmail the named contact. This address receives the verification code,
     *     and later the temporary password, and the person behind it becomes the
     *     company's first administrator.
     */
    protected String registerBusiness(String companyName, String contactEmail) throws Exception {
        String start =
                """
                {"companyName":"%s",
                 "registrationNumber":"RDB-%s",
                 "tin":"TIN-%s",
                 "businessType":"Private limited company",
                 "sector":"Manufacturing",
                 "address":"KG 11 Ave, Nyarutarama, Kigali",
                 "companyEmail":"info@%s",
                 "companyPhone":"0788123456",
                 "existingAccountNumber":"4001234567891",
                 "contactFullName":"Company Contact",
                 "contactRole":"Finance Director",
                 "contactEmail":"%s",
                 "contactPhone":"0781999888",
                 "signatories":[
                   {"fullName":"Company Contact","role":"Finance Director",
                    "nationalId":"1199570099999999","email":"%s","phone":"0781999888"}],
                 "documentNames":["Certificate of incorporation.pdf"]}
                """
                        .formatted(
                                companyName,
                                /*
                                 * Derived from the contact address so two companies in one
                                 * test never collide on the registration number or the TIN
                                 * — neither is unique in the schema today, and a test that
                                 * relied on that would start failing the day one of them is.
                                 */
                                Integer.toHexString(contactEmail.hashCode()),
                                Integer.toHexString(contactEmail.hashCode()),
                                contactEmail.substring(contactEmail.indexOf('@') + 1),
                                contactEmail,
                                contactEmail);

        JsonNode challenge =
                json.readTree(
                        mvc.perform(
                                        post("/api/v1/registration/business/start")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(start))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        var message = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(contactEmail).get(0);
        var matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(message.body());
        assertThat(matcher.find()).as("the verification email should carry a code").isTrue();

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
                        post("/api/v1/registration/business/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"verificationToken":"%s"}"""
                                                .formatted(
                                                        verified.get("verificationToken")
                                                                .asString())))
                .andExpect(status().isCreated());

        return applications.findAll().stream()
                .filter(a -> a.email().equalsIgnoreCase(contactEmail))
                .findFirst()
                .orElseThrow()
                .id()
                .toString();
    }

    /** The administrator's create-account call, with the accounts they typed. */
    protected MockHttpServletRequestBuilder createAccountRequest(String applicationId, String body) {
        return asStaff(
                ADMIN_EMAIL,
                post("/api/v1/admin/applications/{id}/create-account", applicationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    /** Approves the customer and returns the temporary password out of the email. */
    protected String approveAndReadTemporaryPassword(String customerId, String email)
            throws Exception {
        mvc.perform(asStaff(MANAGER_EMAIL, post("/api/v1/admin/customers/{id}/approve", customerId)))
                .andExpect(status().isOk());

        var mail = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).get(0);
        var matcher = Pattern.compile("Temporary password: (\\S+)").matcher(mail.body());
        assertThat(matcher.find())
                .as("the approval email should carry the temporary password")
                .isTrue();
        return matcher.group(1);
    }

    /**
     * Signs in with the temporary password, replaces it, and returns the session that
     * results.
     *
     * <p>THE SESSION CHANGES, which is why this is a helper rather than a copy per class.
     * Replacing the temporary password invalidates the session it was used on and issues a
     * new one — deliberate, because the session's privileges just changed and an id valid
     * under the lesser state must not be valid under the greater one. Carrying the old
     * session forward gets 401, which is the system working.
     */
    protected MockHttpSession signInAndSettle(String email, String temporary) throws Exception {
        MockHttpSession first = new MockHttpSession();

        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(first)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}"""
                                                .formatted(email, temporary)))
                .andExpect(status().isOk());

        var settled =
                mvc.perform(
                                post("/api/v1/auth/password/change-temporary")
                                        .session(first)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"currentPassword":"%s","newPassword":"ZigamaCustomer9!"}"""
                                                        .formatted(temporary)))
                        // 204: the password changed, and there is nothing to return.
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getRequest()
                        .getSession(false);

        return (MockHttpSession) settled;
    }

    /** Registers, creates the login with `body`, approves, signs in. Returns the session. */
    protected MockHttpSession customerWithAccounts(String email, String body) throws Exception {
        String applicationId = registerApplicant(email);
        mvc.perform(createAccountRequest(applicationId, body)).andExpect(status().isOk());

        String customerId =
                customers.findAll().stream()
                        .filter(c -> c.email().equalsIgnoreCase(email))
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .toString();

        return signInAndSettle(email, approveAndReadTemporaryPassword(customerId, email));
    }
}
