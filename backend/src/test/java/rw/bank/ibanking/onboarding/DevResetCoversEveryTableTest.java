package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import rw.bank.ibanking.onboarding.repo.NotificationRepository;

/**
 * /dev/reset really empties the bank, with a customer who has rows in every child table.
 *
 * <p>WHY THIS EXISTS. {@code DevResetController} deletes some children explicitly and
 * relies on {@code ON DELETE CASCADE} for the rest, and its own comment says the cascade
 * "does not" happen on H2 — which is where the whole suite runs. Nothing tested that claim.
 * Two tests do call {@code /dev/reset}, but neither had a customer holding a beneficiary, a
 * service request, a trusted device or a notification, so the ordering was never exercised
 * against the tables added since.
 *
 * <p>WHAT BREAKS IF IT IS WRONG: the reset returns 500 and the developer is left with a
 * half-emptied database — customers gone in one table and not another — which is a worse
 * state than not resetting at all. It is a development endpoint, so this would be found by
 * hand eventually; the point of a test is that it is found by the suite instead.
 */
@DisplayName("Dev reset covers every child table")
class DevResetCoversEveryTableTest extends OnboardingIntegrationTest {

    @Autowired private NotificationRepository notificationRepository;

    private static final String ACCOUNTS =
            """
            {"accounts":[{"accountNumber":"4007777777777","accountType":"CURRENT",\
            "currency":"RWF","openingBalance":"9000"}]}""";

    /** Signs in an administrator the way the portal does. Only they may reset. */
    private MockHttpSession admin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(
                        post("/api/v1/auth/staff/login")
                                .session(session)
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"admin@zigama.local",\
                                        "password":"ZigamaStaff1"}"""))
                .andExpect(status().isOk());
        return session;
    }

    @Test
    @DisplayName("empties a customer who has rows in every table that points at them")
    void emptiesACustomerWithChildrenEverywhere() throws Exception {
        MockHttpSession customer = customerWithAccounts("everything@example.rw", ACCOUNTS);

        /*
         * A notification, which is the row this test was added for. Produced through the
         * real path — changing the password writes one — rather than inserted directly, so
         * it carries whatever a genuine row carries.
         */
        mvc.perform(
                        post("/api/v1/security/password")
                                .session(customer)
                                .with(csrf())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"currentPassword":"ZigamaCustomer9!",\
                                        "newPassword":"Correct-Horse-9-Battery"}"""))
                .andExpect(status().isOk());

        assertThat(notificationRepository.findAll()).isNotEmpty();
        assertThat(signIns.findAll()).isNotEmpty();
        assertThat(customers.findAll()).isNotEmpty();

        mvc.perform(post("/api/v1/admin/dev/reset").with(csrf()).session(admin()))
                .andExpect(status().isNoContent());

        assertThat(customers.findAll()).isEmpty();
        assertThat(notificationRepository.findAll())
                .as("a notification outliving its customer is a half-emptied database")
                .isEmpty();
        assertThat(signIns.findAll()).isEmpty();
    }
}
