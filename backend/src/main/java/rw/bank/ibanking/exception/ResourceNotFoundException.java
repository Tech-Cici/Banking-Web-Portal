package rw.bank.ibanking.exception;

import org.springframework.http.HttpStatus;
import rw.bank.ibanking.common.api.ApiErrorCode;

/**
 * The requested resource does not exist, or the caller is not entitled to see it.
 *
 * <p>Deliberately conflated with "not entitled": telling an unentitled caller that an
 * account exists is itself a disclosure. Do not include the identifier in the message.
 */
public class ResourceNotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String safeMessage) {
        super(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, safeMessage);
    }
}
