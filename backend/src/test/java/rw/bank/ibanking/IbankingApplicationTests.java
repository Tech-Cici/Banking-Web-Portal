package rw.bank.ibanking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Guards the wiring: configuration-properties binding, the security filter chain and the
 * datasource must all resolve. Most misconfiguration shows up here first.
 */
@SpringBootTest
@ActiveProfiles("test")
class IbankingApplicationTests {

    @Test
    @DisplayName("application context loads")
    void contextLoads() {
        // Failure to start is the assertion.
    }
}
