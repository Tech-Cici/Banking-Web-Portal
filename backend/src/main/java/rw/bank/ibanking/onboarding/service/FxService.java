package rw.bank.ibanking.onboarding.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.bank.ibanking.common.money.Money;
import rw.bank.ibanking.exception.BusinessRuleException;
import rw.bank.ibanking.exception.ResourceNotFoundException;
import rw.bank.ibanking.onboarding.domain.CustomerAccountEntity;
import rw.bank.ibanking.onboarding.domain.MovementKind;
import rw.bank.ibanking.onboarding.domain.TransactionDirection;
import rw.bank.ibanking.fx.FxQuoteEntity;
import rw.bank.ibanking.fx.FxQuoteRepository;
import rw.bank.ibanking.fx.FxRateEntity;
import rw.bank.ibanking.fx.FxRateRepository;
import rw.bank.ibanking.onboarding.repo.CustomerAccountRepository;

/**
 * Converting one of a customer's currencies into another, at the bank's own rates.
 *
 * <p>THE RATE IS THE BANK'S AND THE ARITHMETIC IS HERE. Nothing client-side multiplies an
 * amount to show the other side: the figure a customer agrees to is the figure this class
 * computed and stored on a quote, and confirming names that quote rather than repeating any
 * numbers. A client that cannot send amounts cannot send different ones.
 *
 * <p>EVERY CONVERSION GOES THROUGH FRANCS. The bank publishes a buy and a sell rate per
 * foreign currency against RWF, so dollars to pounds is dollars to francs to pounds and pays
 * the spread on each leg. That is not a shortcut -- it is what the trade costs.
 *
 * <p>NO SEPARATE FEE. The margin is the gap between the buy and the sell rate. A fee line
 * beside it would tell the customer they paid twice for the same thing, so the quote reports
 * zero and means it.
 *
 * <p>IT LIVES IN THIS PACKAGE, not beside the fx entities, because it writes to the ledger.
 * {@code AccountLedgerService.postAgainst} is package-private on purpose: the set of classes
 * that may create a money movement is meant to be small enough to read, and it is exactly the
 * services in here. Widening it to public so this class could sit in a tidier package would
 * trade that boundary for a filing preference.
 *
 * <p>IT DOES NOT WAIT FOR A MANAGER, by the same rule a move between a customer's own
 * accounts follows. Nothing leaves the customer. What protects the bank is the quote's
 * expiry, not a queue -- and a queue would make it worse, because a held rate cannot survive
 * one and the customer would be shown one figure and given another.
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    /** The bank's home currency: the one every rate is quoted against. */
    public static final String HOME = "RWF";

    /** Enough working precision that rounding happens once, at the end, deliberately. */
    private static final MathContext MATHS = new MathContext(20, RoundingMode.HALF_UP);

    private final FxRateRepository rates;
    private final FxQuoteRepository quotes;
    private final CustomerAccountRepository accounts;
    private final AccountAccess access;
    private final AccountHolders holders;
    private final AccountLedgerService ledger;

    FxService(
            FxRateRepository rates,
            FxQuoteRepository quotes,
            CustomerAccountRepository accounts,
            AccountAccess access,
            AccountHolders holders,
            AccountLedgerService ledger) {
        this.rates = rates;
        this.quotes = quotes;
        this.accounts = accounts;
        this.access = access;
        this.holders = holders;
        this.ledger = ledger;
    }

    /* =============================================================== quoting */

    /** Every currency the bank currently deals in, home currency included. */
    @Transactional(readOnly = true)
    public List<String> dealtCurrencies() {
        List<String> list = new java.util.ArrayList<>();
        list.add(HOME);
        rates.findAllByOrderByCurrencyAsc().forEach(rate -> list.add(rate.currency()));
        return List.copyOf(list);
    }

    /**
     * Holds a rate for this customer for a couple of minutes.
     *
     * <p>THE QUOTE IS NOT TIED TO AN ACCOUNT. The customer chooses which account funds it when
     * they confirm, and the only thing that has to match is the currency. Binding it here
     * would mean a customer who changed their mind about which account to sell from had to
     * ask for a new rate, for no reason the bank cares about.
     */
    @Transactional
    public FxQuoteEntity quote(UUID customerId, Money sell, String buyCurrency) {
        String buy = normalise(buyCurrency);
        String sellCurrency = normalise(sell.currency());

        if (sellCurrency.equals(buy)) {
            throw new BusinessRuleException("Choose two different currencies.");
        }
        if (sell.minorUnits() <= 0) {
            throw new BusinessRuleException("Enter an amount greater than zero.");
        }

        BigDecimal sellMajor = new BigDecimal(sell.toPlainString());

        /* To francs, then out of francs. One of the two legs is a no-op when RWF is an end. */
        BigDecimal inFrancs = toFrancs(sellMajor, sellCurrency);
        BigDecimal buyMajor = fromFrancs(inFrancs, buy);

        /*
         * ROUNDED DOWN TO THE CURRENCY'S OWN SCALE, once, at the end.
         *
         * Down rather than half-up because the fraction is the bank's: giving a customer more
         * than the quoted rate justifies is the bank paying for its own rounding, on every
         * trade, forever. It is at most one minor unit -- a franc, or a cent.
         *
         * Money.parse then re-checks the scale against the currency, so a mistake here
         * becomes a refusal rather than a figure with too many decimals.
         */
        int scale = Money.scaleOf(buy);
        BigDecimal rounded = buyMajor.setScale(scale, RoundingMode.DOWN);

        if (rounded.signum() <= 0) {
            throw new BusinessRuleException(
                    "That is too little to convert into " + buy + ". Try a larger amount.");
        }

        FxQuoteEntity held =
                quotes.save(
                        FxQuoteEntity.held(
                                customerId,
                                sell,
                                Money.parse(rounded.toPlainString(), buy),
                                describeRate(sellCurrency, buy, sellMajor, buyMajor)));

        log.info(
                "Quoted {} {} to {} {} for customer {} ({})",
                sell.toPlainString(),
                sellCurrency,
                held.buyAmount().toPlainString(),
                buy,
                customerId,
                held.rateText());

        return held;
    }

    @Transactional(readOnly = true)
    public FxQuoteEntity quoteFor(UUID customerId, UUID quoteId) {
        return quotes.findById(quoteId)
                .filter(row -> row.customerId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("We could not find that rate."));
    }

    /* ============================================================ converting */

    /**
     * Sells one currency and buys the other, at the rate already held.
     *
     * <p>ONE TRANSACTION. The debit, the credit and the spending of the quote commit together.
     * A conversion that took the francs and failed to deliver the dollars is the one outcome
     * that must not be reachable.
     *
     * <p>THE QUOTE IS SPENT FIRST, before any money moves. If it has expired or has already
     * been used, nothing should have happened yet.
     */
    @Transactional
    public FxQuoteEntity convert(
            UUID customerId,
            UUID quoteId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            String idempotencyKey) {

        FxQuoteEntity quote = quoteFor(customerId, quoteId);
        quote.consume(Instant.now());
        quotes.save(quote);

        Money sell = quote.sellAmount();
        Money buy = quote.buyAmount();

        CustomerAccountEntity source = heldOrRefuse(customerId, sourceAccountId);

        if (!source.currency().equals(sell.currency())) {
            throw new BusinessRuleException(
                    "That rate sells " + sell.currency() + ", and that account is held in "
                            + source.currency() + ".");
        }
        if (!source.isActive()) {
            throw new BusinessRuleException("That account cannot be used right now.");
        }

        CustomerAccountEntity destination = heldOrRefuse(customerId, destinationAccountId);

        if (!destination.currency().equals(buy.currency())) {
            throw new BusinessRuleException(
                    "That rate buys " + buy.currency() + ", and that account is held in "
                            + destination.currency() + ".");
        }
        if (!destination.isActive()) {
            throw new BusinessRuleException("That account cannot receive money right now.");
        }
        if (destination.id().equals(source.id())) {
            throw new BusinessRuleException("Choose two different accounts.");
        }

        /*
         * THE DESCRIPTION CARRIES THE RATE, on both legs. A statement read six months later
         * has to answer "what rate did I get?", and the two amounts alone cannot: they are in
         * different currencies, so the line that explains them is the rate itself.
         */
        String note = "Currency conversion — " + quote.rateText();

        ledger.postAgainst(
                source,
                TransactionDirection.DEBIT,
                sell,
                note,
                holders.nameOf(source),
                idempotencyKey + "-sold",
                MovementKind.FX_SOLD,
                null,
                null);

        ledger.postAgainst(
                destination,
                TransactionDirection.CREDIT,
                buy,
                note,
                holders.nameOf(destination),
                idempotencyKey + "-bought",
                MovementKind.FX_BOUGHT,
                null,
                null);

        log.info(
                "Converted {} {} to {} {} for customer {}",
                sell.toPlainString(),
                sell.currency(),
                buy.toPlainString(),
                buy.currency(),
                customerId);

        return quote;
    }

    /* =============================================================== the maths */

    /** What the bank would give in francs for this much of that currency. */
    private BigDecimal toFrancs(BigDecimal amount, String currency) {
        if (HOME.equals(currency)) return amount;
        return amount.multiply(rateOrRefuse(currency).buyRate(), MATHS);
    }

    /** How much of that currency those francs would buy. */
    private BigDecimal fromFrancs(BigDecimal francs, String currency) {
        if (HOME.equals(currency)) return francs;
        return francs.divide(rateOrRefuse(currency).sellRate(), MATHS);
    }

    private FxRateEntity rateOrRefuse(String currency) {
        return rates.findByCurrency(currency)
                .orElseThrow(
                        () ->
                                new BusinessRuleException(
                                        "The bank does not deal in " + currency + " today."));
    }

    /**
     * The rate as a sentence, with its units.
     *
     * <p>A BARE DECIMAL DOES NOT SAY WHICH WAY ROUND IT RUNS, and the direction is the whole
     * meaning: 1,450 is a sensible price for a dollar and an absurd one for a franc. So the
     * quote carries a statement rather than a number, and the figures that move are the two
     * amounts beside it.
     *
     * <p>DERIVED FROM THE TWO AMOUNTS rather than from a rate looked up again, so it cannot
     * describe a trade different from the one being offered.
     */
    private static String describeRate(
            String sellCurrency, String buyCurrency, BigDecimal sellMajor, BigDecimal buyMajor) {

        if (sellMajor.signum() == 0) return "—";

        /*
         * QUOTED THE WAY A BOARD READS IT: francs per unit of the foreign currency, which is
         * the number above 1 and the one a Rwandan customer recognises. For a conversion with
         * no franc side, it reads per unit of what is being sold.
         */
        boolean perSold = HOME.equals(buyCurrency);
        BigDecimal perUnit =
                perSold
                        ? buyMajor.divide(sellMajor, MATHS)
                        : sellMajor.divide(buyMajor, MATHS);

        String unit = perSold ? sellCurrency : buyCurrency;
        String priced = perSold ? buyCurrency : sellCurrency;

        return "1 " + unit + " = " + perUnit.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " " + priced;
    }

    /* ============================================================== plumbing */

    private static String normalise(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private CustomerAccountEntity heldOrRefuse(UUID customerId, UUID accountId) {
        return accounts.findById(accountId)
                .filter(row -> access.mayAct(customerId, row))
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "We could not find that account on your profile."));
    }

    /**
     * The customer's active accounts in a currency, so the screen can offer them.
     *
     * <p>THIS USED TO PICK ONE AND NOT SAY WHICH -- a current account if there was one, and
     * otherwise the oldest account in that currency, on a comment claiming there was nothing
     * to choose between. There is: a customer holding voluntary savings and a fixed deposit in
     * dollars has two very different places for the money to land, and a fixed deposit is a
     * locked term rather than somewhere to put a conversion. Deciding that by assignment date
     * is the same defect as resolving a payee by taking the first account that matched, which
     * this project has now corrected twice.
     *
     * <p>AN EMPTY LIST MEANS NO CONVERSION, and the caller says so. Opening an account because
     * somebody pressed convert would be opening a bank account on their behalf.
     */
    @Transactional(readOnly = true)
    public List<CustomerAccountEntity> accountsThatCanReceive(UUID customerId, String currency) {
        return holders.accountsOf(customerId).stream()
                .filter(account -> currency.equals(account.currency()))
                .filter(CustomerAccountEntity::isActive)
                .toList();
    }
}
