package rw.bank.ibanking.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The single error envelope for every non-2xx API response.
 *
 * <p>One shape for all failures means the front end needs exactly one normaliser.
 * {@code correlationId} is echoed so a user can quote it to support and an engineer
 * can find the matching server log line.
 *
 * <p>Never carries stack traces, SQL, upstream payloads, account numbers or any other
 * detail that is unsafe to show a customer.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        ApiErrorCode code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors) {

    public static ApiErrorResponse of(
            int status, ApiErrorCode code, String message, String path, String correlationId) {
        return new ApiErrorResponse(
                Instant.now(), status, code, message, path, correlationId, List.of());
    }

    public static ApiErrorResponse of(
            int status,
            ApiErrorCode code,
            String message,
            String path,
            String correlationId,
            List<FieldViolation> fieldErrors) {
        return new ApiErrorResponse(
                Instant.now(), status, code, message, path, correlationId, fieldErrors);
    }
}
