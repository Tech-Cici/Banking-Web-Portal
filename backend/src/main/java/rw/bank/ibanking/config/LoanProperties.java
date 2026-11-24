package rw.bank.ibanking.config;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import rw.bank.ibanking.common.money.Money;

/**
 * Loan products the bank publishes, and their rates.
 *
 * <p>CONFIGURATION, NOT INVENTION, AND THE DIFFERENCE MATTERS. A rate is a fact about the
 * bank that this service cannot derive; the alternatives were to hard-code one somewhere in
 * the simulation (where nobody would find it) or to seed one into the database (where it
 * would look like something the bank had said). Here it sits in one file, beside a comment
 * saying it is provisional, and becomes correct in a one-line commit.
 *
 * <p>AN UNKNOWN PRODUCT IS REFUSED rather than defaulted. A default rate is a quote the
 * bank never gave.
 */
@ConfigurationProperties(prefix = "ibanking.loans")
public record LoanProperties(List<Product> products, BigDecimal maxTotalExposureRwf) {

    /**
     * The most one customer may owe the bank across every loan at once, in francs.
     *
     * <p>ONE FIGURE FOR THE BANK, NOT ONE PER PRODUCT, and that is a deliberate difference
     * from the collateral thresholds above. A ceiling answers "how much will we let this
     * person owe us", which is a fact about the customer rather than about the product they
     * happen to be asking for. Per-product ceilings would also be trivially defeated: borrow
     * to the limit on a personal loan, then again on a business one.
     *
     * <p>IT IS WHAT MAKES A SETTLED LOAN STOP BLOCKING. A loan that is paid off has nothing
     * outstanding, so it adds nothing to the total and the next application is judged on its
     * own. No separate rule about settled loans is needed, and no status has to be special
     * cased -- which is why this is a sum rather than a count.
     *
     * <p>NULL TURNS THE GATE OFF. A bank that has not set a ceiling has not decided on one,
     * and refusing every application because a number is missing would be the software making
     * a credit policy by omission.
     */
    public BigDecimal maxTotalExposureRwf() {
        return maxTotalExposureRwf;
    }


    /**
     * One product the bank publishes.
     *
     * @param collateral whether this product needs security
     * @param collateralRequiredAboveRwf the figure, in francs, that {@link
     *     CollateralRule#ABOVE_THRESHOLD} compares against -- and nothing at all under the
     *     other two rules
     */
    public record Product(
            String code,
            String name,
            BigDecimal annualRatePercent,
            int minMonths,
            int maxMonths,
            CollateralRule collateral,
            BigDecimal collateralRequiredAboveRwf) {

        /**
         * A product whose security rule does not make sense stops the application starting.
         *
         * <p>DELIBERATELY FATAL. Every other way of handling a half-configured credit rule
         * ends with the bank lending unsecured because a number was missing: a default of zero
         * demands security for everything, a default of infinity demands it for nothing, and
         * both look like the rule working. A service that cannot say whether a loan needs
         * security has no business taking applications.
         */
        public Product {
            if (collateral == null) collateral = CollateralRule.NEVER;

            boolean hasFigure =
                    collateralRequiredAboveRwf != null
                            && collateralRequiredAboveRwf.signum() > 0;

            if (collateral == CollateralRule.ABOVE_THRESHOLD && !hasFigure) {
                throw new IllegalArgumentException(
                        "Loan product "
                                + code
                                + " asks for collateral above a threshold but names no figure."
                                + " Set collateral-required-above-rwf.");
            }
            if (collateral != CollateralRule.ABOVE_THRESHOLD && hasFigure) {
                throw new IllegalArgumentException(
                        "Loan product "
                                + code
                                + " carries a collateral threshold but its rule is "
                                + collateral
                                + ", under which the figure means nothing. Remove one of them.");
            }
            if (collateral == CollateralRule.ABOVE_THRESHOLD
                    && collateralRequiredAboveRwf.scale() > 0
                    && collateralRequiredAboveRwf.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException(
                        "Loan product "
                                + code
                                + " has a collateral threshold with a fraction of a franc."
                                + " The franc has no subdivision here.");
            }
        }

        /**
         * Whether an application for this amount has to pledge something.
         *
         * <p>STRICTLY ABOVE. A loan at exactly the figure is not above it, and the property is
         * named so that reads the way it behaves.
         *
         * <p>ANY CURRENCY BUT THE FRANC NEEDS SECURITY, whatever the amount. The threshold is
         * configured in francs alone, and the alternative to this line is comparing a dollar
         * amount against a franc figure -- which is an exchange rate this portal does not have
         * and must not invent. Erring towards asking is the only safe direction: the opposite
         * mistake is an unsecured loan nobody decided to make.
         */
        public boolean requiresCollateral(Money amount) {
            return switch (collateral) {
                case NEVER -> false;
                case ALWAYS -> true;
                case ABOVE_THRESHOLD ->
                        !"RWF".equals(amount.currency())
                                || amount.isGreaterThan(
                                        Money.parse(
                                                collateralRequiredAboveRwf.toPlainString(), "RWF"));
            };
        }
    }

    public Product require(String code) {
        if (products == null) return null;
        return products.stream()
                .filter(p -> p.code().equalsIgnoreCase(code == null ? "" : code.trim()))
                .findFirst()
                .orElse(null);
    }
}
