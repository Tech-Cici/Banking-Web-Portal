package rw.bank.ibanking.fx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * A rate the bank has held for one customer, for a short while.
 *
 * <p>WHY THE FIGURES ARE STORED RATHER THAN RECOMPUTED. The customer is shown an amount and
 * then asked to confirm it. If the rate were looked up again at confirmation, the amount they
 * agreed to and the amount they receive could differ, and the difference would appear only
 * after the money moved. So both sides are computed once, here, and confirming names the
 * quote instead of repeating the numbers -- which also means a client cannot send different
 * ones.
 *
 * <p>SPENT ONCE. A held rate is an option the bank wrote; one that could be exercised again
 * tomorrow, at a price that has since moved, is an option it did not mean to write.
 */
@Entity
@Table(name = "fx_quotes")
public class FxQuoteEntity {

    /**
     * How long a quote is good for.
     *
     * <p>LONG ENOUGH TO READ A REVIEW SCREEN, short enough that the bank is not exposed to a
     * market that has moved. Two minutes is the shape of the decision being made: look at two
     * numbers, press confirm.
     */
    public static final Duration VALIDITY = Duration.ofMinutes(2);

    @Id private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "sell_currency", nullable = false, length = 3)
    private String sellCurrency;

    @Column(name = "sell_amount_minor", nullable = false)
    private long sellAmountMinor;

    @Column(name = "buy_currency", nullable = false, length = 3)
    private String buyCurrency;

    @Column(name = "buy_amount_minor", nullable = false)
    private long buyAmountMinor;

    @Column(name = "rate_text", nullable = false, length = 64)
    private String rateText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected FxQuoteEntity() {
        // JPA.
    }

    private FxQuoteEntity(UUID customerId, Money sell, Money buy, String rateText) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.sellCurrency = sell.currency();
        this.sellAmountMinor = sell.minorUnits();
        this.buyCurrency = buy.currency();
        this.buyAmountMinor = buy.minorUnits();
        this.rateText = rateText;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(VALIDITY);
    }

    public static FxQuoteEntity held(UUID customerId, Money sell, Money buy, String rateText) {
        return new FxQuoteEntity(customerId, sell, buy, rateText);
    }

    /**
     * Marks this quote as exercised, refusing if it cannot be.
     *
     * <p>THE CHECKS LIVE HERE, on the row, rather than in whichever service happens to be
     * calling. Expiry and single use are properties of the quote itself, and a second caller
     * added later would otherwise have to remember both.
     */
    public void consume(Instant now) {
        if (consumedAt != null) {
            throw new BusinessRuleException(
                    "That rate has already been used. Get a new one to convert again.");
        }
        if (!now.isBefore(expiresAt)) {
            throw new BusinessRuleException(
                    "That rate has expired. Get a new one -- the figures may have changed.");
        }
        this.consumedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public Money sellAmount() {
        return new Money(sellAmountMinor, sellCurrency);
    }

    public Money buyAmount() {
        return new Money(buyAmountMinor, buyCurrency);
    }

    public String rateText() {
        return rateText;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean isSpent() {
        return consumedAt != null;
    }
}
