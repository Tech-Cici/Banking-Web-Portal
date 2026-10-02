package rw.bank.ibanking.web.dto;

import java.time.Instant;

/**
 * Liveness payload for the front end's connectivity check.
 *
 * <p>Carries no build internals, dependency status or environment detail: this endpoint is
 * unauthenticated, so it must not become a reconnaissance tool. Dependency health lives
 * behind the secured actuator endpoints instead.
 *
 * @param status  constant {@code "UP"} when the application can serve requests
 * @param service logical service name
 * @param time    server time, which also lets the client detect clock skew
 */
public record HealthResponse(String status, String service, Instant time) {

    public static HealthResponse up(String service) {
        return new HealthResponse("UP", service, Instant.now());
    }
}
