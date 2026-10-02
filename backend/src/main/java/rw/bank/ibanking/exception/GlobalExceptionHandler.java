package rw.bank.ibanking.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import rw.bank.ibanking.common.api.ApiErrorCode;
import rw.bank.ibanking.common.api.ApiErrorResponse;
import rw.bank.ibanking.common.api.FieldViolation;
import rw.bank.ibanking.common.correlation.CorrelationId;

/**
 * Translates every exception into the single {@link ApiErrorResponse} envelope.
 *
 * <p>Two rules hold throughout:
 *
 * <ul>
 *   <li>Client-fixable faults (4xx) may describe what is wrong. Server faults (5xx) return a
 *       generic message; the detail goes to the log under the correlation id.
 *   <li>Nothing derived from an exception's own message is returned for a 5xx, because those
 *       messages routinely contain SQL, hostnames and upstream payloads.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_SERVER_MESSAGE =
            "We could not complete your request. Please try again, or quote the reference below to support.";

    /** Application exceptions already carry a safe message and a status. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException ex, HttpServletRequest request) {
        /*
         * THE REFERENCE THE CUSTOMER IS SHOWN IS IN THIS LINE, and it was not before.
         *
         * Every error screen in the portal ends with "quote reference 6AZN-HQWX", and
         * this line recorded the code, the status and the path — so a reference a
         * customer read down the telephone matched nothing in the log, and a support
         * call could not be turned into a diagnosis. Worse for development: three
         * different refusals inside one endpoint share a code and a path, so the line
         * could not even say which one fired.
         *
         * The message goes in too. Every ApiException message is already written to be
         * safe to show a customer, so there is nothing here that was not on their
         * screen — and it is the only thing that distinguishes "we could not find that
         * account" from "we could not find an account with that number" when both are a
         * 404 on the same path.
         */
        log.warn(
                "Handled API exception: ref={} code={} status={} path={} message={}",
                CorrelationId.current(),
                ex.code(),
                ex.status().value(),
                request.getRequestURI(),
                ex.getMessage());

        ResponseEntity<ApiErrorResponse> response =
                build(ex.status(), ex.code(), ex.getMessage(), request, ex.fieldErrors());

        /*
         * Retry-After, for a 429 only.
         *
         * The client reads this header to tell someone how long to wait — "please wait 2
         * minutes" rather than "try again later", which is the difference between useful
         * advice and none. Without it the front end has nothing to put in the sentence.
         */
        if (ex instanceof RateLimitedException limited) {
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .header("Retry-After", String.valueOf(limited.retryAfter().toSeconds()))
                    .body(response.getBody());
        }

        return response;
    }

    /** {@code @Valid} failure on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<FieldViolation> violations =
                ex.getBindingResult().getFieldErrors().stream().map(GlobalExceptionHandler::toViolation).toList();

        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_FAILED,
                "Some of the details supplied are not valid.",
                request,
                violations);
    }

    /** {@code @Validated} failure on a path variable, query param or service argument. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        List<FieldViolation> violations =
                ex.getConstraintViolations().stream().map(GlobalExceptionHandler::toViolation).toList();

        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_FAILED,
                "Some of the details supplied are not valid.",
                request,
                violations);
    }

    /** Unreadable/malformed JSON. The parser message is not echoed: it can quote the payload. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Malformed request body on {}", request.getRequestURI());
        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "The request body could not be read.",
                request,
                List.of());
    }

    /**
     * A constraint on a method PARAMETER rather than on a request body.
     *
     * <p>Spring raises this instead of {@link MethodArgumentNotValidException} as soon as a
     * controller method carries a constraint annotation directly on one of its parameters
     * — and it then raises it for that method's {@code @Valid @RequestBody} failures too.
     * So adding one annotation to one parameter silently reroutes every validation failure
     * on that method here.
     *
     * <p>THAT IS EXACTLY WHAT WENT WRONG. It was unmapped, so a customer sending a
     * malformed amount to the deposit endpoint got a 500 reading "something went wrong on
     * our side" — the bank apologising for the customer's typo, and an error the support
     * desk would chase as an outage. It is a 400, like every other validation failure.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleParameterValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {

        List<FieldViolation> violations =
                ex.getParameterValidationResults().stream()
                        .flatMap(
                                result ->
                                        result.getResolvableErrors().stream()
                                                .map(
                                                        error ->
                                                                toViolation(result, error)))
                        .toList();

        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_FAILED,
                "Some of the details supplied are not valid.",
                request,
                violations);
    }

    /**
     * A required header is missing.
     *
     * <p>Mapped here because of {@code Idempotency-Key}, which the deposit and withdrawal
     * endpoints require and deliberately do not generate a fallback for — a generated key
     * turns every retry into a second payment. A client that omits it has that bug, and it
     * needs to be told so with a 400 rather than shown a 500 that reads as the bank's
     * fault.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {

        log.debug("Missing header {} on {}", ex.getHeaderName(), request.getRequestURI());

        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "A required request header is missing.",
                request,
                List.of());
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadParameter(
            Exception ex, HttpServletRequest request) {
        return build(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.BAD_REQUEST,
                "A required request parameter is missing or of the wrong type.",
                request,
                List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}", request.getRequestURI());
        return build(
                HttpStatus.FORBIDDEN,
                ApiErrorCode.FORBIDDEN,
                "You are not permitted to perform this action.",
                request,
                List.of());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoHandler(
            NoHandlerFoundException ex, HttpServletRequest request) {
        return build(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.NOT_FOUND,
                "The requested endpoint does not exist.",
                request,
                List.of());
    }

    /**
     * Last resort. The exception is logged in full with its correlation id; the client gets a
     * generic message and that id.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.INTERNAL_ERROR,
                GENERIC_SERVER_MESSAGE,
                request,
                List.of());
    }

    /**
     * The field the screen should highlight.
     *
     * <p>For a nested body field Spring resolves the name itself and it is the first
     * argument of the error's code list; for a bare parameter it is the parameter name.
     * Falling back to the parameter means a violation always names SOMETHING — a
     * validation error with a null field is one the form cannot highlight, which is how a
     * customer ends up reading "check the highlighted boxes" with nothing highlighted.
     */
    private static FieldViolation toViolation(
            org.springframework.validation.method.ParameterValidationResult result,
            org.springframework.context.MessageSourceResolvable error) {

        if (error instanceof FieldError fieldError) {
            return toViolation(fieldError);
        }

        /*
         * A bare parameter rather than a body field. Falling back to the parameter's own
         * name means a violation always names SOMETHING — a validation error with no field
         * is one the form cannot highlight, which is how a customer ends up reading "check
         * the highlighted boxes" with nothing highlighted.
         */
        String name = result.getMethodParameter().getParameterName();
        String[] codes = error.getCodes();
        return new FieldViolation(
                name == null ? "request" : name,
                codes == null || codes.length == 0 ? "Invalid" : codes[0],
                error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage());
    }

    private static ResponseEntity<ApiErrorResponse> build(
            HttpStatus status,
            ApiErrorCode code,
            String message,
            HttpServletRequest request,
            List<FieldViolation> fieldErrors) {

        ApiErrorResponse body =
                ApiErrorResponse.of(
                        status.value(),
                        code,
                        message,
                        request.getRequestURI(),
                        CorrelationId.current(),
                        fieldErrors);

        return ResponseEntity.status(status).body(body);
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(
                error.getField(),
                error.getCode() == null ? "Invalid" : error.getCode(),
                error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage());
    }

    private static FieldViolation toViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        String constraint =
                violation.getConstraintDescriptor() == null
                        ? "Invalid"
                        : violation
                                .getConstraintDescriptor()
                                .getAnnotation()
                                .annotationType()
                                .getSimpleName();
        return new FieldViolation(field, constraint, violation.getMessage());
    }
}
