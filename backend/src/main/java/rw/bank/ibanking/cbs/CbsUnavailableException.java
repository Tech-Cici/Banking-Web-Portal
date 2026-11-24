package rw.bank.ibanking.cbs;

import org.springframework.http.HttpStatus;
import rw.bank.ibanking.common.api.ApiErrorCode;
import rw.bank.ibanking.exception.ApiException;

/**
 * The core banking system could not be asked.
 *
 * <p>DISTINCT FROM FINDING NOTHING, and the distinction is the point. "There is no such
 * account" and "we could not check" must never reach a member of staff looking the same:
 * the first is a reason to refuse an application, the second is a reason to wait. A single
 * empty result for both would eventually get somebody's application rejected because a
 * database was restarting.
 *
 * <p>503 rather than 500 -- the request was fine and the service is fine; a system this one
 * depends on is not. {@code INTERNAL_ERROR} is the nearest code this project's enum has,
 * and the status carries the meaning.
 */
public class CbsUnavailableException extends ApiException {

    private static final long serialVersionUID = 1L;

    public CbsUnavailableException(String safeMessage) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.INTERNAL_ERROR, safeMessage);
    }
}
