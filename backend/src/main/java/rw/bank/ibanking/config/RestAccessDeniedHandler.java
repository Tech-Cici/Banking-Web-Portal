package rw.bank.ibanking.config;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import rw.bank.ibanking.common.api.ApiErrorCode;
import rw.bank.ibanking.common.api.ApiErrorResponse;
import rw.bank.ibanking.common.correlation.CorrelationId;

/** Returns the standard JSON error envelope for authenticated-but-unentitled requests. */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {

        ApiErrorResponse body =
                ApiErrorResponse.of(
                        HttpStatus.FORBIDDEN.value(),
                        ApiErrorCode.FORBIDDEN,
                        "You are not permitted to perform this action.",
                        request.getRequestURI(),
                        CorrelationId.current());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
