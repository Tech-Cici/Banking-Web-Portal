package rw.bank.ibanking.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import rw.bank.ibanking.common.correlation.CorrelationId;

/**
 * Covers the Phase 1 contract the front end depends on: the health endpoint is public,
 * everything else is denied in the standard envelope, and correlation ids behave.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("health endpoint is public and reports UP")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("ibanking-api"))
                .andExpect(jsonPath("$.time").exists());
    }

    @Test
    @DisplayName("health response carries a generated correlation id")
    void healthReturnsCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationId.HEADER, matchesPattern("[A-Za-z0-9_-]{8,64}")));
    }

    @Test
    @DisplayName("a client-supplied correlation id is echoed back")
    void echoesSuppliedCorrelationId() throws Exception {
        String supplied = "req-0123456789abcdef";

        mockMvc.perform(get("/api/v1/health").header(CorrelationId.HEADER, supplied))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationId.HEADER, supplied));
    }

    @Test
    @DisplayName("an unsafe correlation id is discarded, not reflected")
    void rejectsUnsafeCorrelationId() throws Exception {
        String forged = "abcdefgh\nWARN  spoofed log line";

        mockMvc.perform(get("/api/v1/health").header(CorrelationId.HEADER, forged))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationId.HEADER, not(forged)))
                .andExpect(header().string(CorrelationId.HEADER, matchesPattern("[A-Za-z0-9_-]{8,64}")));
    }

    @Test
    @DisplayName("an unauthenticated request is denied in the standard error envelope")
    void deniesUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/v1/accounts"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("denial response leaks no stack trace or exception detail")
    void denialLeaksNothing() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }
}
