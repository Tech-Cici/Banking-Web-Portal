package rw.bank.ibanking.fx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the bank buys and sells one foreign currency for, in francs.
 *
 * <p>TWO RATES, NOT ONE. {@code buyRate} is what the bank pays for a unit -- the customer is
 * selling it -- and {@code sellRate} is what it charges. The gap is the bank's margin, which
 * is why a conversion carries no separate fee: inventing one would tell the customer they
 * paid twice for the same thing.
 *
 * <p>ALWAYS AGAINST RWF. V32 explains why there is no row per ordered pair: a table of every
 * combination is one staff have to keep consistent with itself, and the first disagreement
 * between USD/GBP and the two legs through francs is the bank quoting two prices for one
 * trade.
 */
@Entity
@Table(name = "fx_rates")
public class FxRateEntity {

    @Id
    @Column(length = 3)
    private String currency;

    @Column(name = "buy_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal buyRate;

    @Column(name = "sell_rate", nullable = false, precision = 18, scale = 6)
    private BigDecimal sellRate;

    @Column(name = "set_at", nullable = false)
    private Instant setAt;

    @Column(name = "set_by_name", nullable = false, length = 160)
    private String setByName;

    protected FxRateEntity() {
        // JPA.
    }

    public String currency() {
        return currency;
    }

    /** Francs the bank pays for one unit. The customer is selling this currency. */
    public BigDecimal buyRate() {
        return buyRate;
    }

    /** Francs the bank charges for one unit. The customer is buying this currency. */
    public BigDecimal sellRate() {
        return sellRate;
    }

    public Instant setAt() {
        return setAt;
    }

    public String setByName() {
        return setByName;
    }

    /** Records a new pair of rates, replacing whatever this currency was quoted at before. */
    public void quotedBy(BigDecimal buy, BigDecimal sell, String staffName) {
        this.buyRate = buy;
        this.sellRate = sell;
        this.setAt = Instant.now();
        this.setByName = staffName;
    }
}
