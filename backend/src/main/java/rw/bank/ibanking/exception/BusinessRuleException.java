package rw.bank.ibanking.exception;

import java.util.List;
import org.springframework.http.HttpStatus;
import rw.bank.ibanking.common.api.ApiErrorCode;
import rw.bank.ibanking.common.api.FieldViolation;

/**
 * A syntactically valid request rejected by a business rule — limit exceeded, account
 * ineligible, beneficiary in a cooling-off period.
 *
 * <p>Maps to 422, which the blueprint (section 26) specifies the client should surface as
 * a safe server message rather than a field error.
 */
public class BusinessRuleException extends ApiException {

    private static final long serialVersionUID = 1L;

    public BusinessRuleException(String safeMessage) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.BUSINESS_RULE_VIOLATION, safeMessage);
    }

    public BusinessRuleException(String safeMessage, List<FieldViolation> fieldErrors) {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ApiErrorCode.BUSINESS_RULE_VIOLATION,
                safeMessage,
                fieldErrors);
    }
}
