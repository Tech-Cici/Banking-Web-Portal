package rw.bank.ibanking.common.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * An amount of money, held as a whole number of minor units.
 *
 * <p>NEVER A DOUBLE, AND NEVER A FLOAT. 0.1 + 0.2 is not 0.3 in binary floating point, and
 * a bank that adds a hundred such amounts is wrong by an amount somebody eventually
 * notices. Everything here is {@code long} minor units — 1234 at scale 2 is 12.34 — and
 * the scale comes from the currency rather than being assumed.
 *
 * <p>SCALE IS NOT ALWAYS 2, which matters here more than it would elsewhere. RWF has NO
 * minor unit: 1000 RWF is 1000 minor units, not 100000. Assuming two decimal places would
 * make every Rwandan franc amount a hundred times too small or too large depending on which
 * end of the conversion got it wrong. {@code Currency.getDefaultFractionDigits()} is the
 * authority, and it agrees with the {@code Intl} rule the portal's money module uses, so
 * both ends of the wire compute the same number.
 *
 * <p>OVER-PRECISION IS AN ERROR, NOT SOMETHING TO ROUND. "12.345" in a 2-decimal currency
 * is refused rather than quietly turned into 12.34 or 12.35. Rounding somebody's money
 * without telling them is how a discrepancy becomes untraceable — and the direction chosen
 * is always a policy decision that nobody made.
 */
public record Money(long minorUnits, String currency) {

    public Money {
        currency = currency.toUpperCase(Locale.ROOT);
    }

    /** The currency's minor-unit digits: 2 for USD, 0 for RWF. */
    public static int scaleOf(String currency) {
        try {
            int digits = Currency.getInstance(currency.toUpperCase(Locale.ROOT))
                    .getDefaultFractionDigits();
            /*
             * -1 means "no minor unit defined" (gold, for instance). Treated as 0 rather
             * than as an error: it is a whole-unit currency, which is the same arithmetic
             * as RWF.
             */
            return Math.max(digits, 0);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(
                    "We do not recognise the currency " + currency + ".");
        }
    }

    /**
     * Parses a decimal string such as "1500" or "12.34".
     *
     * <p>Strict about precision on purpose — see the class comment.
     */
    public static Money parse(String amount, String currency) {
        int scale = scaleOf(currency);

        BigDecimal value;
        try {
            value = new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            throw new BusinessRuleException("That is not an amount we can read.");
        }

        if (value.scale() > scale) {
            throw new BusinessRuleException(
                    scale == 0
                            ? currency + " amounts do not have decimal places."
                            : currency + " amounts have at most " + scale + " decimal places.");
        }

        if (value.signum() < 0) {
            throw new BusinessRuleException("An amount cannot be negative.");
        }

        /*
         * movePointRight then longValueExact: exact, and it THROWS rather than truncating
         * if the amount is too large for a long. A silently wrapped balance is the worst
         * possible failure in a ledger.
         */
        return new Money(value.movePointRight(scale).longValueExact(), currency);
    }

    /** Minor units back to a decimal string the portal can render. */
    public String toPlainString() {
        return BigDecimal.valueOf(minorUnits).movePointLeft(scaleOf(currency)).toPlainString();
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            /*
             * No implicit conversion, ever. Adding USD to RWF at an assumed rate is how a
             * portal invents money; if a rate is needed, somebody has to choose it
             * explicitly and record which one they used.
             */
            throw new BusinessRuleException(
                    "Those amounts are in different currencies (" + currency + " and "
                            + other.currency + ").");
        }
    }
}
