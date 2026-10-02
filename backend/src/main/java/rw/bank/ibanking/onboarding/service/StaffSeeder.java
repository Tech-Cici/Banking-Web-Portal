package rw.bank.ibanking.onboarding.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.domain.StaffRole;
import rw.bank.ibanking.onboarding.repo.StaffRepository;

/**
 * Creates the two bootstrap staff logins, in development and test only.
 *
 * <p>{@code @Profile("dev", "h2", "test")} is the guard, and it is the important line in
 * this file: seeded credentials in a production database are an unowned account with a
 * known password. A deployed environment creates its first administrator through a process
 * that leaves an audit trail, not from a source file.
 *
 * <p>There are deliberately NO seeded customers. The only way into the customer portal is
 * to register, have an administrator create the account and a manager approve it — which is
 * exactly the chain most worth testing, and a fixture login is a way to skip it.
 */
@Configuration
@Profile({"dev", "h2", "test"})
class StaffSeeder {

    private static final Logger log = LoggerFactory.getLogger(StaffSeeder.class);

    /** Development only. Never a production password. */
    static final String DEV_PASSWORD = "ZigamaStaff1";

    @Bean
    ApplicationRunner seedStaff(StaffRepository staff, PasswordEncoder passwords) {
        return args -> {
            if (staff.count() > 0) return;

            staff.save(
                    new StaffEntity(
                            "Jean Claude Nkurunziza",
                            "admin@zigama.local",
                            StaffRole.ADMIN,
                            "Head office",
                            passwords.encode(DEV_PASSWORD)));

            staff.save(
                    new StaffEntity(
                            "Immaculee Mukandayisenga",
                            "manager@zigama.local",
                            StaffRole.MANAGER,
                            "Head office",
                            passwords.encode(DEV_PASSWORD)));

            log.warn(
                    "Seeded two DEVELOPMENT staff logins (admin@zigama.local,"
                            + " manager@zigama.local). This runs only under the dev, h2 and test"
                            + " profiles.");
        };
    }
}
