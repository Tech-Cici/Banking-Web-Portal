package rw.bank.ibanking.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.bank.ibanking.web.dto.HealthResponse;

/**
 * The one endpoint Phase 1 exposes. It exists so the front end's API client, CORS
 * configuration, error envelope and correlation-id plumbing can be verified end to end
 * before any banking endpoint is written.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name:ibanking-api}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return HealthResponse.up(serviceName);
    }
}
