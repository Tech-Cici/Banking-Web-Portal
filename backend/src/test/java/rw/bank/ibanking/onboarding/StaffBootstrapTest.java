package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import rw.bank.ibanking.onboarding.domain.StaffRole;
import rw.bank.ibanking.onboarding.repo.StaffRepository;

/**
 * THE FIRST TWO STAFF LOGINS IN A DEPLOYED ENVIRONMENT.
 *
 * <p>WHAT THIS GUARDS. {@code StaffSeeder} only runs under {@code dev}, {@code h2} and
 * {@code test}, and nothing else in the codebase creates a {@code StaffEntity} — so every
 * other profile started with an empty {@code staff} table and a staff portal nobody could
 * enter. The service came up cleanly and could not onboard a single customer.
 *
 * <p>THESE TESTS RUN ON THE {@code demo} PROFILE, not {@code test}, which is the only way
 * to exercise {@code StaffBootstrap} at all: its {@code @Profile("!dev & !h2 & !test")}
 * exists precisely to keep it away from the suite's own profile. Each nested class gets its
 * own application context, which is the cost of testing a thing that only happens at
 * start-up.
 *
 * <p>The datasource is named per class so the contexts do not share an H2 database — two
 * bootstraps against one database would see each other's rows and the "did nothing" cases
 * would pass for the wrong reason.
 *
 * <p>EVERY CLASS ALSO SETS A DATABASE PASSWORD AND A CORS ORIGIN, which look like noise and
 * are not. {@code DemoEnvironmentCheck} guards this same profile and refuses to start when
 * either is missing — so these two lines are what let the profile come up at all, and
 * leaving them out would make these tests fail for a reason that has nothing to do with
 * bootstrapping staff. A blank password would count as missing, which is deliberate: it is
 * never right for the database this profile is aimed at.
 */
@DisplayName("Bootstrapping the first staff logins")
class StaffBootstrapTest {

    private static final String STRONG = "Correct-Horse-9-Battery";

    @Nested
    @SpringBootTest
    @ActiveProfiles("demo")
    @TestPropertySource(
            properties = {
                "spring.datasource.url=jdbc:h2:mem:bootstrap-ok;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=h2",
                "ibanking.cors.allowed-origins=http://localhost:5173",
                "spring.flyway.enabled=true",
                "BOOTSTRAP_ADMIN_EMAIL=admin@zigama.rw",
                "BOOTSTRAP_ADMIN_PASSWORD=" + STRONG,
                "BOOTSTRAP_MANAGER_EMAIL=manager@zigama.rw",
                "BOOTSTRAP_MANAGER_PASSWORD=" + STRONG,
                "BOOTSTRAP_ADMIN_NAME=Alice Admin",
            })
    @DisplayName("with the environment set")
    class WhenConfigured {

        @Autowired private StaffRepository staff;
        @Autowired private PasswordEncoder passwords;

        @Test
        @DisplayName("creates one administrator and one manager, and nothing else")
        void createsBothRoles() {
            /*
             * THE TEST THE DEPLOYMENT NEEDED. Without this runner the count here is zero and
             * the staff portal is unreachable — which is how the service behaved on every
             * profile but three.
             */
            assertThat(staff.findAll()).hasSize(2);

            assertThat(staff.findAll())
                    .extracting(member -> member.role())
                    .containsExactlyInAnyOrder(StaffRole.ADMIN, StaffRole.MANAGER);

            var admin = staff.findByEmailIgnoreCase("admin@zigama.rw").orElseThrow();
            assertThat(admin.role()).isEqualTo(StaffRole.ADMIN);
            assertThat(admin.fullName()).isEqualTo("Alice Admin");

            /*
             * THE PASSWORD IS HASHED, NOT STORED. Asserted by checking the encoder matches
             * it rather than by comparing strings — a test that compared the stored value to
             * the plaintext would pass only if the bug were present.
             */
            assertThat(passwords.matches(STRONG, admin.passwordHash())).isTrue();
            assertThat(admin.passwordHash()).isNotEqualTo(STRONG);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("demo")
    @TestPropertySource(
            properties = {
                "spring.datasource.url=jdbc:h2:mem:bootstrap-bare;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=h2",
                "ibanking.cors.allowed-origins=http://localhost:5173",
                "spring.flyway.enabled=true",
            })
    @DisplayName("with nothing configured")
    class WhenNotConfigured {

        @Autowired private StaffRepository staff;

        @Test
        @DisplayName("creates nobody when nothing at all is set")
        void createsNobody() {
            /*
             * A DEFAULT WOULD BE A KNOWN CREDENTIAL ON A BANKING SERVICE, which is the exact
             * failure StaffSeeder's profile guard exists to prevent. The service still
             * starts — refusing to boot would make a configuration mistake look like an
             * outage — and logs an error naming the four variables to set.
             */
            assertThat(staff.findAll()).isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("demo")
    @TestPropertySource(
            properties = {
                "spring.datasource.url=jdbc:h2:mem:bootstrap-nopw;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=h2",
                "ibanking.cors.allowed-origins=http://localhost:5173",
                "spring.flyway.enabled=true",
                /*
                 * ADDRESSES SET, PASSWORDS ABSENT. This is the case that actually catches a
                 * default password, and the first version of this test class did not have
                 * it — the "nothing configured" case below short-circuits on the empty
                 * email before the password is ever read, so a `orDefault(..., "Zigama…")`
                 * slipped straight past a green suite. Verified by putting that default
                 * in: this case goes red and that one does not.
                 */
                "BOOTSTRAP_ADMIN_EMAIL=admin@zigama.rw",
                "BOOTSTRAP_MANAGER_EMAIL=manager@zigama.rw",
            })
    @DisplayName("with addresses but no passwords")
    class WhenPasswordsAreMissing {

        @Autowired private StaffRepository staff;

        @Test
        @DisplayName("creates nobody — there is no default password anywhere")
        void refusesWithoutPasswords() {
            /*
             * A DEFAULT PASSWORD ON A BANKING SERVICE IS A PUBLISHED CREDENTIAL. The
             * repository contains StaffSeeder's development password in plain text, so a
             * fallback to anything at all would mean a deployed instance whose admin login
             * is readable on GitHub.
             */
            assertThat(staff.findAll()).isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("demo")
    @TestPropertySource(
            properties = {
                "spring.datasource.url=jdbc:h2:mem:bootstrap-weak;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=h2",
                "ibanking.cors.allowed-origins=http://localhost:5173",
                "spring.flyway.enabled=true",
                "BOOTSTRAP_ADMIN_EMAIL=admin@zigama.rw",
                "BOOTSTRAP_ADMIN_PASSWORD=short",
                "BOOTSTRAP_MANAGER_EMAIL=manager@zigama.rw",
                "BOOTSTRAP_MANAGER_PASSWORD=" + STRONG,
            })
    @DisplayName("with a weak password")
    class WhenPasswordIsWeak {

        @Autowired private StaffRepository staff;

        @Test
        @DisplayName("refuses both, so a deployment cannot be weaker than its own policy")
        void refusesWeakPasswords() {
            /*
             * The same policy the customers get, from the same method. A deployment
             * bootstrapped with "short" would hold a weaker credential than the service
             * accepts from the people whose money it holds.
             *
             * BOTH are refused, not just the weak one: a half-bootstrapped environment with
             * a manager and no administrator cannot create an account, so it is no more
             * usable than an empty one and harder to understand.
             */
            assertThat(staff.findAll()).isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("demo")
    @TestPropertySource(
            properties = {
                "spring.datasource.url=jdbc:h2:mem:bootstrap-same;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=h2",
                "ibanking.cors.allowed-origins=http://localhost:5173",
                "spring.flyway.enabled=true",
                "BOOTSTRAP_ADMIN_EMAIL=both@zigama.rw",
                "BOOTSTRAP_ADMIN_PASSWORD=" + STRONG,
                "BOOTSTRAP_MANAGER_EMAIL=both@zigama.rw",
                "BOOTSTRAP_MANAGER_PASSWORD=" + STRONG,
            })
    @DisplayName("with one address for both roles")
    class WhenOneAddressHoldsBothRoles {

        @Autowired private StaffRepository staff;

        @Test
        @DisplayName("refuses, because sign-in looks staff up by email")
        void refusesOneAddressForBothRoles() {
            /*
             * Not merely a four-eyes concern. Sign-in finds staff by email, so a second row
             * on the same address would shadow the first and which role you got would depend
             * on row order — a difference between an administrator and a manager decided by
             * the database.
             */
            assertThat(staff.findAll()).isEmpty();
        }
    }
}
