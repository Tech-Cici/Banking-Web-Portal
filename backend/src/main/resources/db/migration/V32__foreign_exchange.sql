/*
 * FOREIGN EXCHANGE: THE BANK'S RATES, AND THE QUOTES IT HOLDS.
 *
 *
 * 1. RATES ARE QUOTED AGAINST RWF, ONE ROW PER FOREIGN CURRENCY.
 *
 * That is how a Rwandan bank's board reads, and it is also the only shape that stays honest
 * as currencies are added: a row per ORDERED PAIR needs n*(n-1) rows that staff must keep
 * consistent with each other, and the first time USD/GBP disagrees with USD/RWF and RWF/GBP
 * the bank is quoting two different prices for the same trade.
 *
 * A CONVERSION BETWEEN TWO FOREIGN CURRENCIES GOES THROUGH RWF, and pays the spread twice.
 * That is not a simplification, it is what actually happens: the bank buys the dollars for
 * francs and sells pounds for francs, and there is a margin on each leg.
 *
 * RWF HAS NO ROW. The home currency does not have a rate against itself.
 *
 *
 * 2. TWO RATES, NOT ONE, BECAUSE A BANK IS NOT A CALCULATOR.
 *
 * buy_rate is what the bank PAYS in francs for one unit of the currency -- the customer is
 * selling. sell_rate is what it CHARGES -- the customer is buying. The difference is the
 * bank's margin, which is why the quote's fee is zero rather than hidden: there is no
 * separate charge, the margin is in the rate, and a fee line invented to look busy would be
 * money the customer is told they paid twice.
 *
 * sell_rate >= buy_rate IS ENFORCED. The other way round is a bank paying more for a currency
 * than it charges, which is a typing error that would quietly lose money on every trade.
 *
 *
 * 3. QUOTES ARE HELD, AND SPENT ONCE.
 *
 * The customer is shown a figure and then asked to confirm it. Between those two moments the
 * rate must not move, or the amount they agreed to is not the amount they get -- so the
 * figures are computed once, stored, and the confirmation names the quote rather than
 * repeating the numbers. A client that cannot send amounts cannot send different ones.
 *
 * consumed_at IS WHAT STOPS A QUOTE BEING SPENT TWICE. A held rate is an option written by
 * the bank; one that could be exercised repeatedly, at a price that has since moved, is an
 * option the bank did not mean to write.
 *
 *
 * 4. THE SEEDED RATES ARE PROVISIONAL AND SAY SO.
 *
 * Same standing as the loan rates in application.yml: working figures, chosen so the screens
 * can be built and shown, and wrong until Zigama's treasury gives the real ones. They are
 * attributed to this migration rather than to a member of staff, because no person set them.
 * The staff screen that replaces them records who did.
 */

CREATE TABLE fx_rates (
    /* The foreign currency. The rate is always against RWF. */
    currency      CHAR(3)       NOT NULL PRIMARY KEY,

    /* Francs the bank PAYS for one unit. The customer is selling this currency. */
    buy_rate      NUMERIC(18,6) NOT NULL,

    /* Francs the bank CHARGES for one unit. The customer is buying this currency. */
    sell_rate     NUMERIC(18,6) NOT NULL,

    set_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    set_by_name   VARCHAR(160)  NOT NULL,

    CONSTRAINT fx_rate_positive
        CHECK (buy_rate > 0 AND sell_rate > 0),

    /* Paying more than you charge is a typing error that loses money on every trade. */
    CONSTRAINT fx_spread_sane
        CHECK (sell_rate >= buy_rate),

    /* The home currency has no rate against itself. */
    CONSTRAINT fx_rate_not_home
        CHECK (currency <> 'RWF')
);

COMMENT ON TABLE fx_rates IS
    'What the bank buys and sells each foreign currency for, in francs. One row per currency; cross-currency conversions go through RWF and pay the spread on each leg.';

CREATE TABLE fx_quotes (
    id                UUID         NOT NULL PRIMARY KEY,

    customer_id       UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,

    sell_currency     CHAR(3)      NOT NULL,
    sell_amount_minor BIGINT       NOT NULL,

    buy_currency      CHAR(3)      NOT NULL,
    buy_amount_minor  BIGINT       NOT NULL,

    /*
     * The rate as the customer reads it, composed when the quote is made.
     *
     * TEXT, NOT A NUMBER, and deliberately. A bare decimal beside the word "Rate" does not
     * say which way round it runs, and the direction is the whole meaning: 1,450 is a
     * sensible price for a dollar and an absurd one for a franc. The sentence carries its own
     * units. The figures that actually move are the two amounts above, which were computed
     * from the rate at the moment it was quoted.
     */
    rate_text         VARCHAR(64)  NOT NULL,

    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    /* Set when the quote is exercised. A quote is an option the bank wrote once. */
    consumed_at       TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT fx_quote_amounts_positive
        CHECK (sell_amount_minor > 0 AND buy_amount_minor > 0),

    /* Converting a currency into itself is not a conversion. */
    CONSTRAINT fx_quote_currencies_differ
        CHECK (sell_currency <> buy_currency),

    CONSTRAINT fx_quote_expiry_after_creation
        CHECK (expires_at > created_at)
);

CREATE INDEX fx_quotes_owner_idx ON fx_quotes (customer_id, created_at DESC);

COMMENT ON COLUMN fx_quotes.consumed_at IS
    'When this quote was exercised. A quote with a value here cannot be used again: a held rate that could be spent repeatedly, after the market moved, is an option the bank did not mean to write.';

/* ------------------------------------------------------------- provisional rates */

INSERT INTO fx_rates (currency, buy_rate, sell_rate, set_at, set_by_name) VALUES
    ('USD', 1420.000000, 1450.000000, CURRENT_TIMESTAMP, 'Provisional (migration V32)'),
    ('EUR', 1550.000000, 1590.000000, CURRENT_TIMESTAMP, 'Provisional (migration V32)'),
    ('GBP', 1800.000000, 1850.000000, CURRENT_TIMESTAMP, 'Provisional (migration V32)');
