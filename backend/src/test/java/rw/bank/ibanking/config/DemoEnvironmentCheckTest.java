package rw.bank.ibanking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * THE CHECK THAT TURNS A SILENT MISCONFIGURATION INTO A SENTENCE.
 *
 * <p>WHAT WENT WRONG, which is why this exists. {@code application-demo.yml} was written
 * with {@code ${DB_URL:?DB_URL must be set…}} — the shell's "required, with a message"
 * placeholder, which Spring does not have. A colon introduces a DEFAULT, so an unset
 * variable left the literal text {@code ?DB_URL must be set…} in the property and Hikari
 * was asked to connect to it. Running the packaged jar with nothing set produced twenty
 * lines of bean-creation cascade ending in {@code 'url' must start with "jdbc"}, which
 * names neither the variable, nor the profile, nor the fact that a deployment is missing
 * its database. Writing it the plain way produced the identical message.
 *
 * <p>These tests are a plain unit test of the processor rather than five Spring contexts.
 * The logic worth guarding is "what counts as missing", and an unresolved placeholder
 * counting as missing is the part that is easy to lose: {@code getProperty} returns
 * {@code ${DB_URL}} as a perfectly good String, so a null check alone would pass and the
 * original failure would come straight back. That the processor is wired into the
 * {@code demo} profile at all is proven elsewhere — {@code StaffBootstrapTest} starts five
 * contexts on that profile and would not start if this refused them.
 */
@DisplayName("Refusing to start the demo profile with a half-filled environment")
class DemoEnvironmentCheckTest {

    private static final String JDBC = "jdbc:postgresql://ep-x.eu-central-1.aws.neon.tech/db";

    private static MockEnvironment complete() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", JDBC)
                .withProperty("spring.datasource.username", "neon_user")
                .withProperty("spring.datasource.password", "a-password")
                .withProperty("ibanking.cors.allowed-origins", "https://portal.vercel.app");
    }

    private static void check(MockEnvironment environment) {
        DemoEnvironmentCheck processor = new DemoEnvironmentCheck();
        processor.setEnvironment(environment);
        processor.postProcessBeanFactory(null);
    }

    @Test
    @DisplayName("starts when everything is set")
    void startsWhenComplete() {
        assertThatCode(() -> check(complete())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("names an unresolved placeholder as missing, not as a value")
    void treatsUnresolvedPlaceholderAsMissing() {
        /*
         * THE CASE THAT CAUGHT THE ORIGINAL BUG. An unset environment variable does not
         * remove the property — it leaves `${DB_URL}` in it as text. A check that only
         * looked for null would be satisfied by this and the deployment would fail later,
         * deep inside Hikari, saying nothing useful.
         */
        MockEnvironment environment = complete();
        environment.setProperty("spring.datasource.url", "${DB_URL}");

        assertThatThrownBy(() -> check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL is not set");
    }

    @Test
    @DisplayName("names the libpq URL Neon actually hands out")
    void rejectsNeonsOwnUrlFormat() {
        /*
         * THE LIKELIEST MISTAKE IN THIS DEPLOYMENT. Neon's dashboard shows
         * postgresql://user:password@host/db, and pasting it unaltered is the obvious
         * thing to do. It produces the same `must start with "jdbc"` error as an unset
         * variable, so the two have to be told apart in the message or the error sends
         * somebody looking for a variable they have already set.
         */
        MockEnvironment environment = complete();
        environment.setProperty(
                "spring.datasource.url", "postgresql://neon_user:secret@ep-x.neon.tech/db");

        assertThatThrownBy(() -> check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not start with \"jdbc:\"")
                .hasMessageContaining("DB_USERNAME");
    }

    @Test
    @DisplayName("never puts the connection string in the message")
    void doesNotLeakTheConnectionString() {
        /*
         * The message goes to a deployment log, and a libpq URL carries the password in it.
         * Echoing the offending value back would be the natural way to write this error and
         * would publish the credential to every log aggregator downstream.
         */
        MockEnvironment environment = complete();
        environment.setProperty(
                "spring.datasource.url", "postgresql://neon_user:s3cr3t@ep-x.neon.tech/db");

        assertThatThrownBy(() -> check(environment))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(
                        thrown -> {
                            assertThat(thrown.getMessage()).doesNotContain("s3cr3t");
                            assertThat(thrown.getMessage()).doesNotContain("neon_user");
                        });
    }

    @Test
    @DisplayName("treats a blank password as missing")
    void treatsBlankPasswordAsMissing() {
        /*
         * Blank is fine for H2 and never right for the database this profile points at, so
         * it is refused rather than passed through. Named separately because "" and null
         * arrive by different routes — an empty Render field gives the first.
         */
        MockEnvironment environment = complete();
        environment.setProperty("spring.datasource.password", "   ");

        assertThatThrownBy(() -> check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD is not set");
    }

    @Test
    @DisplayName("names the CORS origin, the step that comes after the frontend exists")
    void requiresTheCorsOrigin() {
        /*
         * This one cannot be set before deploying, because it is the frontend's URL — so it
         * is the variable most likely to be left out, and the symptom without it is a portal
         * that loads and then fails every request with no explanation in the browser.
         */
        MockEnvironment environment = complete();
        environment.setProperty("ibanking.cors.allowed-origins", "${CORS_ALLOWED_ORIGINS}");

        assertThatThrownBy(() -> check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ALLOWED_ORIGINS is not set");
    }

    @Test
    @DisplayName("reports every missing variable at once, not the first")
    void reportsAllProblemsTogether() {
        /*
         * Four deploys to learn about four variables is the alternative, and each one is a
         * cold start and a container build.
         */
        assertThatThrownBy(() -> check(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL")
                .hasMessageContaining("DB_USERNAME")
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("CORS_ALLOWED_ORIGINS");
    }
}
