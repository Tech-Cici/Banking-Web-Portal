package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;

/**
 * SIGNING IN ON A PASSWORD ALONE, which is how this portal ships.
 *
 * <p>The emailed code was asked for on every sign-in. Two attempts were made to keep it
 * and make it bearable — remembering one browser, then remembering several — and both
 * failed against how the portal is actually used, so the bank's original instruction
 * stands: the code goes.
 *
 * <p>THE CODE PATH IS NOT DELETED and is still covered by {@code CustomerSignInTest} and
 * {@code TrustedDeviceTest}, which run with {@code code-required: true} from
 * application-test.yml. This class is the other half: it pins the property OFF for itself,
 * so both behaviours are tested and neither depends on which way the default happens to
 * be set.
 *
 * <p>The assertion that matters is not that a session appears — it is that the session
 * WORKS. An outcome of COMPLETE with a session that cannot reach an account would be the
 * same bug as before, wearing a different message.
 */
@DisplayName("Signing in without a code")
@TestPropertySource(properties = "ibanking.sign-in.code-required=false")
class PasswordOnlySignInTest extends OnboardingIntegrationTest {

    private static final String PASSWORD = "ZigamaCustomer9!";

    private static final String ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4007777777777","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"1000"}]}""";

    @Test
    @DisplayName("A PASSWORD IS ENOUGH, and no code is emailed")
    void aPasswordAloneSignsIn() throws Exception {
        String email = "no-code@example.rw";
        customerWithAccounts(email, ACCOUNTS);

        int mailBefore = outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email).size();

        MockHttpSession session = new MockHttpSession();

        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(session)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"%s"}"""
                                                .formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETE"))
                /*
                 * And no challenge in the payload. An outcome of COMPLETE beside a
                 * challenge the client might still navigate to would send the customer to
                 * a code screen for a code that was never sent.
                 */
                .andExpect(jsonPath("$.challenge").doesNotExist());

        /*
         * NOTHING WAS EMAILED. Asserted on the outbox rather than trusted, because the
         * failure worth catching is a code still being generated and sent while the
         * screen no longer asks for it — a customer getting sign-in codes they never
         * requested is how somebody concludes their account is being attacked.
         */
        assertThat(outbox.findByRecipientIgnoreCaseOrderBySentAtDesc(email)).hasSize(mailBefore);

        /* And no challenge was recorded to be spent later. */
        assertThat(challenges.findAll()).isEmpty();

        /*
         * THE SESSION WORKS. This is the assertion that would have caught the last two
         * rounds of this: an outcome string is not a session, and a session that cannot
         * reach an account is the same failure in a nicer wrapper.
         */
        mvc.perform(get("/api/v1/accounts").session(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A WRONG PASSWORD IS STILL REFUSED, and says nothing about which part was wrong")
    void aWrongPasswordIsStillRefused() throws Exception {
        String email = "still-guarded@example.rw";
        customerWithAccounts(email, ACCOUNTS);

        /*
         * Turning the second factor off must not soften the first one. The message is the
         * same for a wrong password and an unknown address, so neither tells somebody
         * which of the two they got right.
         */
        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(new MockHttpSession())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"%s","password":"NotThePassword1!"}"""
                                                .formatted(email)))
                .andExpect(status().isUnauthorized());

        mvc.perform(
                        post("/api/v1/auth/login")
                                .session(new MockHttpSession())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"nobody@example.rw","password":"%s"}"""
                                                .formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }
}
