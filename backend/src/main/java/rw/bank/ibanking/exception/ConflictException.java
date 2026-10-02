package rw.bank.ibanking.exception;

import org.springframework.http.HttpStatus;
import rw.bank.ibanking.common.api.ApiErrorCode;

/**
 * The request conflicts with current state: a replayed idempotency key with a different
 * payload, a stale optimistic-lock version, an already-cancelled request.
 *
 * <p>The client must not blindly resubmit on 409 (blueprint section 26).
 */
public class ConflictException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String safeMessage) {
        super(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, safeMessage);
    }
}
