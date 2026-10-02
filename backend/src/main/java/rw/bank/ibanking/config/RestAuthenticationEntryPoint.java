package rw.bank.ibanking.config;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import rw.bank.ibanking.common.api.ApiErrorCode;
import rw.bank.ibanking.common.api.ApiErrorResponse;
import rw.bank.ibanking.common.correlation.CorrelationId;

/**
 * Returns the standard JSON error envelope for unauthenticated requests instead of
 * redirecting to a login page or issuing a {@code WWW-Authenticate} challenge.
 *
 * <p>A browser-visible basic-auth dialog on an SPA API would be both confusing and a
 * credential-phishing surface, so no challenge header is sent.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {

        ApiErrorResponse body =
                ApiErrorResponse.of(
                        HttpStatus.UNAUTHORIZED.value(),
                        ApiErrorCode.UNAUTHENTICATED,
                        "Your session is not valid. Please sign in again.",
                        request.getRequestURI(),
                        CorrelationId.current());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
