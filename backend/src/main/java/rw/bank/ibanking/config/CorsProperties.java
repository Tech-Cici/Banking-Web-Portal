package rw.bank.ibanking.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS allow-list, bound from {@code ibanking.cors.*}.
 *
 * <p>Origins are configuration, never code: the dev origin is the Vite server, and each
 * deployed environment supplies its own. Wildcards are rejected at startup because this API
 * is credentialed.
 *
 * @param allowedOrigins exact scheme://host:port origins permitted to call the API
 * @param allowedMethods HTTP methods permitted cross-origin
 * @param allowedHeaders request headers the browser may send
 * @param exposedHeaders response headers the browser may read
 * @param allowCredentials whether cookies/authorization may accompany cross-origin requests
 * @param maxAgeSeconds how long a browser may cache the preflight result
 */
@ConfigurationProperties(prefix = "ibanking.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        List<String> allowedMethods,
        List<String> allowedHeaders,
        List<String> exposedHeaders,
        boolean allowCredentials,
        long maxAgeSeconds) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedMethods =
                allowedMethods == null || allowedMethods.isEmpty()
                        ? List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        : List.copyOf(allowedMethods);
        allowedHeaders =
                allowedHeaders == null || allowedHeaders.isEmpty()
                        /*
                         * X-XSRF-TOKEN is not optional. The portal runs on a different
                         * origin from the API in development, so a header the preflight
                         * does not allow never reaches the CSRF filter — every
                         * authenticated POST then fails 403 with nothing in the response
                         * that points at CORS as the cause.
                         */
                        ? List.of(
                                "Authorization",
                                "Content-Type",
                                "X-Correlation-Id",
                                "Idempotency-Key",
                                "X-XSRF-TOKEN")
                        : List.copyOf(allowedHeaders);
        exposedHeaders =
                exposedHeaders == null || exposedHeaders.isEmpty()
                        ? List.of("X-Correlation-Id")
                        : List.copyOf(exposedHeaders);
        maxAgeSeconds = maxAgeSeconds <= 0 ? 1800L : maxAgeSeconds;

        if (allowedOrigins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalArgumentException(
                    "ibanking.cors.allowed-origins must list exact origins; wildcards are not "
                            + "permitted for a credentialed banking API.");
        }
    }
}
