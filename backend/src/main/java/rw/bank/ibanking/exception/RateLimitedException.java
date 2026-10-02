package rw.bank.ibanking.exception;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import rw.bank.ibanking.common.api.ApiErrorCode;

/**
 * The caller has asked for something too often.
 *
 * <p>Maps to 429. Carries how long to wait so the handler can set {@code Retry-After} and
 * the client can say "wait two minutes" rather than "try again later" — the blueprint's
 * standard error experience (section 26) expects the header to be honoured.
 *
 * <p>Used on the unauthenticated registration endpoints, where a limit is not a nicety. An
 * endpoint that sends an email to any address given to it, with no session required, is an
 * open relay without one: anyone could use the bank to deliver mail to a stranger, from
 * the bank's own address.
 */
public class RateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public RateLimitedException(String safeMessage, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.RATE_LIMITED, safeMessage);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
