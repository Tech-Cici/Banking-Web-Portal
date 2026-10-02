package rw.bank.ibanking.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * SESSIONS LIVE IN THE DATABASE, NOT IN TOMCAT'S MEMORY.
 *
 * <p>WHAT WAS REPORTED: "when I reload a page as a signed-in person it redirects me to the
 * sign-in page". It was accurate. {@code HttpSession} lived in the servlet container, so
 * every restart of this service signed every customer out at once — and during a week of
 * shipping migrations that meant several times an hour. V21 moves them into the database.
 *
 * <p>WHY A TEST, AND WHY THIS ASSERTION. The fix is one dependency and two properties, and
 * getting it wrong is SILENT. Declaring {@code spring-session-jdbc} alone — the obvious
 * thing, and what was tried first — puts the library on the classpath with nothing
 * configuring it, because Spring Boot 4 split its auto-configuration into one module per
 * technology and session support no longer ships in the core jar. The application then
 * starts cleanly, logs nothing at all about sessions, and goes on using Tomcat's memory.
 * The only visible difference is the name of a cookie.
 *
 * <p>So a test that merely signed in and asserted success would have passed against the
 * broken configuration. This one asserts against the session store itself: if the rows are
 * not there, the sessions are not in the database, whatever else works.
 *
 * <p>A RESTART IS NOT SIMULATED HERE, because a test cannot restart the container it runs
 * in. It was verified end to end instead: sign in through a browser, kill the API, start a
 * fresh JVM against the same database, reload — the page stayed on /beneficiaries and
 * {@code /session} answered 200. What this test guards is the thing that would quietly
 * undo it.
 */
/*
 * CLEARS THE TEST PROFILE'S EXCLUSION, so this one class runs the PRODUCTION session
 * configuration. application-test.yml switches the JDBC store off for the rest of the
 * suite — see the comment there for why, and for what that costs — and this is the class
 * that pays the cost back.
 *
 * It buys its own application context, which is a few seconds. That is the right price for
 * the only test that can tell a working session store from a silent no-op.
 */
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DisplayName("Sessions are in the database")
class SessionsAreInTheDatabaseTest extends OnboardingIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private long storedSessions() {
        Long count =
                jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("signing in writes a row to SPRING_SESSION")
    void aSignInIsPersisted() throws Exception {
        long before = storedSessions();

        mvc.perform(
                        post("/api/v1/auth/staff/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"identifier":"admin@zigama.local",\
                                        "password":"ZigamaStaff1"}"""))
                .andExpect(status().isOk());

        /*
         * BREAK THE GUARD TO SEE IT FAIL: swap spring-boot-starter-session-jdbc back for a
         * bare spring-session-jdbc, and this count never moves — the application still
         * starts, still signs people in, and still loses every session on restart.
         */
        assertThat(storedSessions())
                .as("a session that is not in SPRING_SESSION is a session a restart destroys")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("the schema is Flyway's, not the library's")
    void theSchemaIsOwnedByFlyway() {
        /*
         * `spring.session.jdbc.initialize-schema` is `never`, so these tables exist only
         * because V21 created them — the same rule that keeps `ddl-auto: none` everywhere.
         * A library creating tables on start-up produces a schema that differs between
         * environments and carries no version anybody can name.
         *
         * Asserted through the migration history rather than by the tables merely
         * existing, because the library creating them would also satisfy that.
         */
        Long applied =
                jdbc.queryForObject(
                        /*
                         * QUOTED, and lower case. Flyway creates its history table with a
                         * quoted lower-case name; H2 folds an UNQUOTED identifier to upper
                         * case, so the bare name misses it and the error names the table it
                         * could not find as a candidate. PostgreSQL folds the other way, so
                         * quoting the exact stored name is the form that works on both —
                         * the same V4 lesson in a test instead of a migration.
                         */
                        "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '21'",
                        Long.class);

        assertThat(applied).isEqualTo(1L);

        /* And the attribute column survived the dialect difference: the library ships BYTEA
           for PostgreSQL and LONGVARBINARY for H2, and V21 writes BYTEA because H2 accepts
           it in PostgreSQL mode. If that ever stops being true, this query fails here
           rather than on a customer's sign-in. */
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES", Long.class))
                .isNotNull();
    }
}
