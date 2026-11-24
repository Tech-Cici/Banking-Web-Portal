package rw.bank.ibanking.cbs;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of the bank's core banking system, as this service reads it.
 *
 * <p>A CBS ROW IS A CUSTOMER, NOT AN ACCOUNT. The source table carries seventeen balance
 * columns -- product crossed with currency -- so the holdings below are an unpivoted view
 * of one row, not seventeen rows.
 *
 * @param holdings only the balances that are NOT zero. A zero column is read as "no such
 *     account", not as an account holding nothing, because the alternative gives every
 *     customer seventeen accounts with sixteen of them empty. The source cannot tell the
 *     two apart, so this is a decision rather than a reading, and it is made in exactly
 *     one place -- here -- so the bank can overturn it in one place.
 */
public record CbsCustomer(
        String accountNumber,
        String name,
        String category,
        String status,
        LocalDate openedOn,
        String telephone,
        List<Holding> holdings) {

    /** One product, in one currency, with the balance the CBS holds for it. */
    public record Holding(String product, String currency, BigDecimal balance) {}

    /** The CBS spells it "Domant". Asking that question here means not spreading it. */
    public boolean dormant() {
        return "Domant".equalsIgnoreCase(status);
    }

    public boolean company() {
        return "C".equalsIgnoreCase(category);
    }
}
