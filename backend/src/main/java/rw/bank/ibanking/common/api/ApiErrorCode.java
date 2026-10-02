package rw.bank.ibanking.common.api;

/**
 * Stable, machine-readable error codes returned to the client.
 *
 * <p>The front end switches on these, never on human-readable text. Codes are additive:
 * never rename or repurpose an existing value once a client depends on it.
 */
public enum ApiErrorCode {

    /** Request body/params failed Bean Validation. Carries fieldErrors. */
    VALIDATION_FAILED,

    /** Malformed request the client can fix (unreadable JSON, bad type, missing param). */
    BAD_REQUEST,

    /** No/expired credentials. Client should renew the session or return to login. */
    UNAUTHENTICATED,

    /** Authenticated but not entitled. Client shows an access-denied message. */
    FORBIDDEN,

    /** Addressed resource does not exist or is not visible to this principal. */
    NOT_FOUND,

    /** Request conflicts with current state (duplicate idempotency key, stale version). */
    CONFLICT,

    /** Business rule rejected the request (limit exceeded, ineligible account). */
    BUSINESS_RULE_VIOLATION,

    /** Client exceeded a rate limit. Honour Retry-After. */
    RATE_LIMITED,

    /** Unexpected server fault. Message is deliberately generic. */
    INTERNAL_ERROR,

    /** A downstream system (CBS, provider) failed or timed out. */
    UPSTREAM_UNAVAILABLE
}
