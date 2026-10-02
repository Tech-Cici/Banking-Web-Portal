package rw.bank.ibanking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much a customer has to do to sign in, bound from {@code ibanking.sign-in.*}.
 *
 * <p>Bound rather than read ad hoc so a typo fails at startup instead of silently leaving
 * a security control at its default. A misspelled {@code code-required} would be a
 * deployment that believes it asks for a second factor and does not.
 *
 * @param codeRequired whether a password alone is enough.
 *     <p>FALSE BY DEFAULT, and that is a deliberate downgrade rather than an oversight.
 *     The bank asked for the emailed code to stop: it was being sent on every sign-in,
 *     and a customer checking their balance twice in a morning read two emails and typed
 *     two codes.
 *     <p>The middle option was tried first — keep the code and remember the browser for
 *     thirty days. It is still in the codebase, still tested, and it is what turning this
 *     back on restores. It is off because it did not survive contact with how the portal
 *     is actually used: several accounts in one browser, several tabs at once. The trust
 *     is per browser and the cookie is shared, so every switch between two accounts asked
 *     for a code again, which is worse than either extreme.
 *     <p>WHAT IS GIVEN UP, stated plainly because a control nobody wrote down as missing
 *     is a control everybody assumes is there: with this false, a leaked password is
 *     enough to reach somebody's money from anywhere in the world. Nothing else stands in
 *     the way. That is an acceptable trade while this is a portal being built and
 *     demonstrated, and it is not acceptable for real customers — see
 *     docs/OPEN-ITEMS.md.
 */
@ConfigurationProperties(prefix = "ibanking.sign-in")
public record SignInProperties(boolean codeRequired) {}
