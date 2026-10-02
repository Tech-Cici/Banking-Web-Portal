package rw.bank.ibanking.exception;

import java.util.List;
import org.springframework.http.HttpStatus;
import rw.bank.ibanking.common.api.ApiErrorCode;
import rw.bank.ibanking.common.api.FieldViolation;

/**
 * Base class for exceptions that map directly onto an HTTP status and an {@link ApiErrorCode}.
 *
 * <p>The message of an {@code ApiException} is considered safe to return to the client, so
 * never build one out of an upstream error string, a SQL message or a stack trace.
 */
public abstract class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final ApiErrorCode code;
    private final transient List<FieldViolation> fieldErrors;

    protected ApiException(HttpStatus status, ApiErrorCode code, String safeMessage) {
        this(status, code, safeMessage, List.of());
    }

    protected ApiException(
            HttpStatus status,
            ApiErrorCode code,
            String safeMessage,
            List<FieldViolation> fieldErrors) {
        super(safeMessage);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() {
        return status;
    }

    public ApiErrorCode code() {
        return code;
    }

    public List<FieldViolation> fieldErrors() {
        return fieldErrors;
    }
}
