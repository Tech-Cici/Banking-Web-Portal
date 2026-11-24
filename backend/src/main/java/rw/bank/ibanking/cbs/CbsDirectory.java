package rw.bank.ibanking.cbs;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.config.CbsProperties;

/**
 * Reads the bank's core banking system.
 *
 * <p>THE POOL IS DELIBERATELY NOT A {@code DataSource} BEAN, and that is the single most
 * important thing about this class. This application's Postgres datasource is created by
 * Spring Boot's auto-configuration, which backs off the moment any {@code DataSource} bean
 * exists in the context. Publishing a second one would therefore take JPA, Flyway and the
 * session store down with it, and the fix would be to hand-wire all three. Building the
 * CBS pool privately, inside a component that is not itself a datasource, leaves the
 * application's own database wiring untouched.
 *
 * <p>READ-ONLY, stated three ways: the pool is marked read-only so the driver refuses
 * writes, nothing here issues anything but SELECT, and the account this connects with
 * should be granted SELECT and nothing else. This service owns its Postgres database and
 * is a guest in the CBS; a guest does not write.
 *
 * <p>OPTIONAL. With {@code ibanking.cbs.enabled} false there is no pool and every lookup
 * reports the CBS as unavailable. A developer with no SQL Server running gets a working
 * application, not a broken startup.
 */
@Component
public class CbsDirectory {

    private static final Logger log = LoggerFactory.getLogger(CbsDirectory.class);

    /**
     * The seventeen balance columns, each with the product and currency it means.
     *
     * <p>Written out rather than derived from the column names. The source spells them
     * inconsistently -- {@code BalanceCurrentAc} beside {@code BalanceCurrentacUSD}, and
     * {@code BalanceCompAc} beside {@code BalancecompSavingsUSD} -- so any rule clever
     * enough to parse them would be one rename away from silently mapping a balance to the
     * wrong product.
     */
    private static final List<Column> COLUMNS =
            List.of(
                    new Column("BalanceCurrentAc", "CURRENT", "RWF"),
                    new Column("BalanceCompAc", "COMPULSORY_SAVINGS", "RWF"),
                    new Column("BalanceVolAc", "VOLUNTARY_SAVINGS", "RWF"),
                    new Column("BalanceFixedac", "FIXED_DEPOSIT", "RWF"),
                    new Column("BalanceEducationSavings", "EDUCATION_SAVINGS", "RWF"),
                    new Column("BalanceCurrentacUSD", "CURRENT", "USD"),
                    new Column("BalancecompSavingsUSD", "COMPULSORY_SAVINGS", "USD"),
                    new Column("BalancevoluSavingsUSD", "VOLUNTARY_SAVINGS", "USD"),
                    new Column("BalanceFixedacUSD", "FIXED_DEPOSIT", "USD"),
                    new Column("BalanceCurrentacEuro", "CURRENT", "EUR"),
                    new Column("BalancecompSavingsEuro", "COMPULSORY_SAVINGS", "EUR"),
                    new Column("BalancevoluSavingsEuro", "VOLUNTARY_SAVINGS", "EUR"),
                    new Column("BalanceFixedacEuro", "FIXED_DEPOSIT", "EUR"),
                    new Column("BalanceCurrentacPound", "CURRENT", "GBP"),
                    new Column("BalancecompSavingsPound", "COMPULSORY_SAVINGS", "GBP"),
                    new Column("BalancevoluSavingsPound", "VOLUNTARY_SAVINGS", "GBP"),
                    new Column("BalanceFixedacPound", "FIXED_DEPOSIT", "GBP"));

    private record Column(String name, String product, String currency) {}

    private static final String SELECT_ONE =
            "SELECT Account, Names, Categ, AStatus, Customercategory, Dateofcreation,"
                    + " TelephoneNumber, "
                    + String.join(", ", COLUMNS.stream().map(Column::name).toList())
                    + " FROM customer_accounts WHERE Account = ?";

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    CbsDirectory(CbsProperties settings) {
        if (!settings.enabled()) {
            this.pool = null;
            this.jdbc = null;
            log.info("The core banking system is switched off; account lookups will report it as unavailable");
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.url());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setPoolName("cbs");
        /* Small: this pool serves staff lookups, not customer traffic. */
        config.setMaximumPoolSize(4);
        config.setReadOnly(true);

        this.pool = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(this.pool);
        log.info("Connected to the core banking system");
    }

    @PreDestroy
    void close() {
        if (pool != null) {
            pool.close();
        }
    }

    /** Whether lookups can be answered at all. */
    public boolean available() {
        return jdbc != null;
    }

    /**
     * Finds one customer by the account number a person typed.
     *
     * <p>BY ACCOUNT NUMBER ONLY, and there is no lookup by name on purpose. Sixty of the
     * two hundred names in this data are duplicates -- four customers called Emmanuel
     * Bizimana, four called Alice Rukundo -- so a name identifies nobody, and a screen
     * that let staff search by one would eventually attach a stranger's balances to an
     * application.
     *
     * @throws CbsUnavailableException if the CBS is switched off or cannot be reached.
     *     Not an empty result: "we could not ask" and "we asked and there is no such
     *     account" must never look the same to the person deciding.
     */
    public Optional<CbsCustomer> findByAccountNumber(String accountNumber) {
        if (jdbc == null) {
            throw new CbsUnavailableException(
                    "The bank's core system is not connected, so this account number cannot be"
                            + " checked right now.");
        }

        String trimmed = accountNumber == null ? "" : accountNumber.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(jdbc.queryForObject(SELECT_ONE, CbsDirectory::map, trimmed));
        } catch (EmptyResultDataAccessException noSuchAccount) {
            return Optional.empty();
        } catch (DataAccessException unreachable) {
            /*
             * Logged in full, reported in one sentence. A JDBC failure carries the url and
             * sometimes the login, and the staff screen is not where either belongs.
             */
            log.error("Could not read the core banking system", unreachable);
            throw new CbsUnavailableException(
                    "The bank's core system did not answer. Try again in a moment.");
        }
    }

    /**
     * The same number, written at the scale its currency actually has.
     *
     * <p>EVERY COLUMN IN THE CBS IS {@code DECIMAL(19,2)}, so a balance arrives with two
     * decimal places whatever the currency -- an RWF figure comes back as "3056912.00".
     * RWF has no minor unit, and {@code Money.parse} refuses anything with a scale larger
     * than its currency allows, so that string was rejected at the counter: "RWF amounts
     * do not have decimal places". Rounding the stored data was necessary and was not
     * enough, because the zeros are still two digits of scale.
     *
     * <p>{@code RoundingMode.UNNECESSARY} rather than a rounding mode, deliberately. This
     * drops zeros and nothing else: if the CBS ever holds a genuine 0.21 RWF, this throws
     * instead of quietly rounding it away. Losing a centime silently is how a portal ends
     * up disagreeing with a teller, and the disagreement is always discovered by a
     * customer.
     */
    private static BigDecimal atCurrencyScale(BigDecimal balance, String currency) {
        return balance.setScale(Money.scaleOf(currency), java.math.RoundingMode.UNNECESSARY);
    }

    private static CbsCustomer map(ResultSet row, int rowNumber) throws SQLException {
        List<CbsCustomer.Holding> holdings = new ArrayList<>();

        for (Column column : COLUMNS) {
            BigDecimal balance = row.getBigDecimal(column.name());

            /*
             * A zero column is read as "no such account". See CbsCustomer for why, and
             * note that this is the only place the rule lives.
             */
            if (balance != null && balance.signum() != 0) {
                holdings.add(
                        new CbsCustomer.Holding(
                                column.product(),
                                column.currency(),
                                atCurrencyScale(balance, column.currency())));
            }
        }

        java.sql.Date opened = row.getDate("Dateofcreation");

        return new CbsCustomer(
                row.getString("Account"),
                row.getString("Names"),
                row.getString("Categ"),
                row.getString("AStatus"),
                opened == null ? null : opened.toLocalDate(),
                row.getString("TelephoneNumber"),
                List.copyOf(holdings));
    }

}
