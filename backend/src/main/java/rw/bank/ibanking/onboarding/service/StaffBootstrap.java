package rw.bank.ibanking.onboarding.service;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import rw.bank.ibanking.onboarding.domain.StaffEntity;
import rw.bank.ibanking.onboarding.domain.StaffRole;
import rw.bank.ibanking.onboarding.repo.StaffRepository;

/**
 * THE FIRST TWO STAFF LOGINS IN A DEPLOYED ENVIRONMENT, FROM THE ENVIRONMENT.
 *
 * <p>WHY THIS HAD TO EXIST BEFORE ANYTHING COULD BE DEPLOYED. {@link StaffSeeder} is
 * {@code @Profile({"dev","h2","test"})} and nothing else in this codebase ever creates a
 * {@code StaffEntity}. So on any other profile the {@code staff} table started empty and
 * stayed empty: nobody could sign into the staff portal, which means no administrator could
 * create a customer account, no manager could approve one, and nobody could read the
 * message log. The service started cleanly and was completely unusable — you could not
 * onboard the first customer, and nothing in the logs said why.
 *
 * <p>StaffSeeder's own comment said a deployed environment "creates its first administrator
 * through a process that leaves an audit trail, not from a source file". That was the right
 * principle and no such process existed. This is it.
 *
 * <p>WHAT IT WILL NOT DO:
 *
 * <ul>
 *   <li><b>It never runs when any staff row exists.</b> This is a bootstrap, not a
 *       reconciliation: an environment that already has staff has its own history, and a
 *       runner that reset a password on every restart would be a backdoor with a deploy
 *       button.
 *   <li><b>It has no default password.</b> A default would be a known credential on a
 *       banking service, which is the entire failure StaffSeeder's profile guard exists to
 *       prevent. If the variable is absent, nothing is created.
 *   <li><b>It never logs the password</b>, only the addresses and roles it created — which
 *       is the audit trail, and is why it logs at WARN rather than DEBUG.
 * </ul>
 *
 * <p>AND IT SAYS SO LOUDLY WHEN IT CANNOT HELP. An empty staff table with no configuration
 * is the unusable state described above, so that combination logs an error naming the exact
 * variables to set. Starting silently is what made this cost an afternoon to diagnose.
 *
 * <p>THE FOUR-EYES RULE IS WEAKENED FOR AS LONG AS ONE PERSON HOLDS BOTH PASSWORDS, and
 * that is worth stating rather than glossing. The portal keeps ADMIN and MANAGER apart so
 * that issuing working credentials takes two people; a deployment seeded from two
 * environment variables starts with whoever set them able to do both. The mitigation is
 * procedural, not technical: hand the two passwords to two different people and have each
 * change their own. There is no screen for creating further staff yet — that is recorded in
 * docs/OPEN-ITEMS.md.
 */
@Configuration
@Profile("!dev & !h2 & !test")
class StaffBootstrap {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    @Bean
    ApplicationRunner bootstrapStaff(
            StaffRepository staff, PasswordEncoder passwords, Environment environment) {

        return args -> {
            /*
             * THE FIRST CHECK, AND THE ONE THAT MATTERS. Everything below only happens on a
             * database with no staff at all.
             */
            if (staff.count() > 0) {
                log.info("Staff logins already exist; the bootstrap did nothing.");
                return;
            }

            String adminEmail = text(environment, "BOOTSTRAP_ADMIN_EMAIL");
            String adminPassword = text(environment, "BOOTSTRAP_ADMIN_PASSWORD");
            String managerEmail = text(environment, "BOOTSTRAP_MANAGER_EMAIL");
            String managerPassword = text(environment, "BOOTSTRAP_MANAGER_PASSWORD");

            if (adminEmail.isEmpty()
                    || adminPassword.isEmpty()
                    || managerEmail.isEmpty()
                    || managerPassword.isEmpty()) {

                /*
                 * ERROR, not WARN, and it does not throw. Refusing to start would make a
                 * database hiccup during a deploy indistinguishable from a configuration
                 * mistake, and the service is still useful to a customer who already has an
                 * account. But a staff portal nobody can enter is not a working deployment,
                 * so it must not be possible to miss this line.
                 */
                log.error(
                        "NO STAFF LOGINS EXIST AND NONE CAN BE CREATED. The staff portal is"
                                + " unreachable, so no account can be created or approved. Set"
                                + " BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD,"
                                + " BOOTSTRAP_MANAGER_EMAIL and BOOTSTRAP_MANAGER_PASSWORD and"
                                + " restart.");
                return;
            }

            if (adminEmail.equalsIgnoreCase(managerEmail)) {
                /*
                 * One address holding both roles would not merely weaken the four-eyes rule
                 * — the sign-in looks up staff by email, so the second row would shadow the
                 * first and which role you got would depend on row order.
                 */
                log.error(
                        "BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_MANAGER_EMAIL are the same address"
                                + " ({}). The two roles are deliberately different people. No"
                                + " staff were created.",
                        adminEmail);
                return;
            }

            /*
             * THE SAME PASSWORD POLICY THE CUSTOMERS GET, from the same method, so a
             * deployment cannot be bootstrapped with a weaker password than the service
             * would accept from the people whose money it holds.
             */
            String adminProblem = CustomerAuthService.passwordProblem(adminPassword);
            String managerProblem = CustomerAuthService.passwordProblem(managerPassword);
            if (adminProblem != null || managerProblem != null) {
                log.error(
                        "A bootstrap password was refused, so no staff were created. Admin: {}."
                                + " Manager: {}.",
                        adminProblem == null ? "acceptable" : adminProblem,
                        managerProblem == null ? "acceptable" : managerProblem);
                return;
            }

            staff.save(
                    new StaffEntity(
                            nameOr(environment, "BOOTSTRAP_ADMIN_NAME", "Bank Administrator"),
                            adminEmail.toLowerCase(Locale.ROOT),
                            StaffRole.ADMIN,
                            nameOr(environment, "BOOTSTRAP_BRANCH", "Head office"),
                            passwords.encode(adminPassword)));

            staff.save(
                    new StaffEntity(
                            nameOr(environment, "BOOTSTRAP_MANAGER_NAME", "Bank Manager"),
                            managerEmail.toLowerCase(Locale.ROOT),
                            StaffRole.MANAGER,
                            nameOr(environment, "BOOTSTRAP_BRANCH", "Head office"),
                            passwords.encode(managerPassword)));

            /*
             * THE AUDIT TRAIL. Addresses and roles, never the passwords — a log line
             * carrying a working credential turns the log into a list of them.
             */
            log.warn(
                    "BOOTSTRAPPED THE FIRST TWO STAFF LOGINS: {} (ADMIN) and {} (MANAGER)."
                            + " Both passwords came from the environment. Hand them to two"
                            + " different people and have each change their own — one person"
                            + " holding both defeats the four-eyes rule. This runner will not"
                            + " run again while any staff row exists.",
                    adminEmail,
                    managerEmail);
        };
    }

    private static String text(Environment environment, String key) {
        String value = environment.getProperty(key);
        return value == null ? "" : value.trim();
    }

    private static String nameOr(Environment environment, String key, String fallback) {
        String value = text(environment, key);
        return value.isEmpty() ? fallback : value;
    }
}
