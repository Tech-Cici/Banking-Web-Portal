package rw.bank.ibanking.config;

/**
 * Whether a loan product needs security, and on what condition.
 *
 * <p>THREE STATES RATHER THAN A THRESHOLD OF ZERO OR INFINITY. "Always" and "never" are
 * decisions the bank has made about a product; expressing them as a threshold of 0 or of some
 * enormous number hides a policy inside a number, and the next person to read the file cannot
 * tell a deliberate "never" from a figure somebody forgot to set.
 */
public enum CollateralRule {

    /** Secured by something other than a pledge -- a salary advance is secured by the salary. */
    NEVER,

    /** Needed once the amount passes the product's figure. Below it, nothing is asked for. */
    ABOVE_THRESHOLD,

    /** Needed whatever the amount. */
    ALWAYS
}
