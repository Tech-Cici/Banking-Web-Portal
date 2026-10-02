package rw.bank.ibanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Internet Banking REST API.
 *
 * <p>Layering (see README): web -> service -> repository. Controllers speak DTOs only;
 * entities never cross the web boundary.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IbankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(IbankingApplication.class, args);
    }
}
