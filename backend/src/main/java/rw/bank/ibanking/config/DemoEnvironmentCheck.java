package rw.bank.ibanking.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.PlaceholderResolutionException;

/**
 * Refuses to start the {@code demo} profile with its environment half-filled, and says which
 * variable is missing.
 *
 * <p>WHY THIS EXISTS AT ALL. {@code application-demo.yml} was first written with
 * {@code ${DB_URL:?DB_URL must be set…}}, on the belief that Spring supports the shell's
 * "required, with a message" placeholder. IT DOES NOT. A colon in a Spring placeholder
 * introduces a DEFAULT, so an unset {@code DB_URL} silently became the literal string
 * {@code ?DB_URL must be set…} and was handed to Hikari as a JDBC URL. Measured, not
 * assumed: the service then died with twenty lines of bean-creation cascade ending in
 *
 * <pre>java.lang.IllegalArgumentException: 'url' must start with "jdbc"</pre>
 *
 * <p>which names neither {@code DB_URL} nor the profile nor the deployment. Writing the
 * placeholder the plain way, {@code ${DB_URL}}, produces exactly the same message — the
 * unresolved text is passed through rather than raising "could not resolve placeholder".
 * Both forms were tried against the packaged jar; this check is what makes the failure
 * legible.
 *
 * <p>WHY A {@code BeanFactoryPostProcessor} and not {@code @PostConstruct}. The datasource
 * is built during context refresh, and the whole value here is reporting BEFORE it is. A
 * BFPP runs after bean definitions are registered and before any ordinary bean is
 * instantiated, so this error is the first thing in the log rather than the twenty-first
 * line of a cascade. {@code @Profile} is honoured because profile conditions are evaluated
 * when definitions are registered, which is earlier still.
 *
 * <p>IT CHECKS THE SHAPE OF {@code DB_URL}, not just its presence, because Neon hands out
 * {@code postgresql://user:password@host/db} — a libpq URL, not a JDBC one — and pasting it
 * unaltered is the single likeliest mistake in this deployment. That paste produces the
 * identical {@code must start with "jdbc"} error, so the check names it explicitly.
 *
 * <p>REFUSING TO START IS THE RIGHT ANSWER HERE, unlike {@link MailStartupCheck} which only
 * warns. A service with no mail still serves every other endpoint; a service with no
 * database serves nothing, and one with no {@code CORS_ALLOWED_ORIGINS} serves nothing to a
 * browser — so a loud refusal is strictly better than a health check that passes while
 * every request fails.
 */
@Component
@Profile("demo")
class DemoEnvironmentCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> problems = new ArrayList<>();

        /*
         * The property name, not the environment variable name, is what is read — relaxed
         * binding means SPRING_DATASOURCE_URL and DB_URL both land here. The MESSAGE names
         * the environment variable, because that is what somebody types into Render.
         */
        String url = text("spring.datasource.url");
        if (url == null) {
            problems.add("DB_URL is not set. It must be the Neon connection string as a JDBC URL.");
        } else if (!url.startsWith("jdbc:")) {
            /*
             * The value is NOT included in the message. A connection string carries the
             * password, and this text goes to a deployment log.
             */
            problems.add(
                    "DB_URL does not start with \"jdbc:\". Neon shows a libpq URL"
                            + " (postgresql://user:password@host/db); this needs the JDBC form"
                            + " (jdbc:postgresql://host/db?sslmode=require) with the username and"
                            + " password moved into DB_USERNAME and DB_PASSWORD.");
        }

        if (text("spring.datasource.username") == null) {
            problems.add("DB_USERNAME is not set.");
        }
        if (text("spring.datasource.password") == null) {
            problems.add("DB_PASSWORD is not set.");
        }
        if (text("ibanking.cors.allowed-origins") == null) {
            problems.add(
                    "CORS_ALLOWED_ORIGINS is not set. It must be the browser app's exact origin"
                            + " (scheme and host, no trailing slash), e.g."
                            + " https://your-portal.vercel.app. Without it the API refuses every"
                            + " request a browser makes and the portal cannot sign anybody in."
                            + " It is set AFTER the frontend exists, which is why it is the step"
                            + " most often forgotten.");
        }

        if (problems.isEmpty()) {
            return;
        }

        throw new IllegalStateException(
                "The demo profile is missing configuration and will not start:"
                        + problems.stream()
                                .map(problem -> System.lineSeparator() + "  - " + problem)
                                .reduce("", String::concat)
                        + System.lineSeparator()
                        + "See backend/src/main/resources/application-demo.yml and"
                        + " frontend/DEPLOY.md.");
    }

    /**
     * The property's value, or null when it is absent, blank, or an unresolved placeholder.
     *
     * <p>THE PLACEHOLDER CASE IS THE WHOLE POINT, and it arrives by TWO DIFFERENT ROUTES
     * depending on who reads the property. Both were measured; neither was guessed.
     *
     * <ul>
     *   <li>{@code Environment.getProperty} resolves placeholders STRICTLY and throws
     *       {@link PlaceholderResolutionException} — "Could not resolve placeholder
     *       'DB_URL'". That is this method's own call, so it has to be caught rather than
     *       allowed to escape, or the refusal would be a stack trace about one variable
     *       instead of a list naming all four.
     *   <li>Boot's configuration-property binding is LENIENT and passes the unresolved text
     *       through. That is how {@code ${DB_URL}} reached Hikari and produced
     *       {@code 'url' must start with "jdbc"} — so the literal has to be recognised too.
     * </ul>
     *
     * <p>A check written for either route alone would miss the other, and the one it missed
     * is the one a deployment would actually hit.
     */
    private String text(String key) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (PlaceholderResolutionException unresolved) {
            return null;
        }

        if (value == null || value.isBlank()) {
            return null;
        }
        return value.contains("${") ? null : value;
    }
}
