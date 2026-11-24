package rw.bank.ibanking.onboarding.domain;

import java.time.Duration;
import java.time.Instant;
import rw.bank.ibanking.common.time.BankZone;

/**
 * How often a standing order pays, and when the next occurrence falls.
 *
 * <p>THE NEXT MOMENT IS COMPUTED HERE so that one rule serves the creation path and the
 * sweep. Two places deciding when a payment is next due is two places that can disagree
 * about somebody's rent.
 *
 * <p>DATE-SHAPED PERIODS GO THROUGH THE BANK'S CALENDAR, not through arithmetic on the
 * instant. "A month from the 5th" is a question about days, so the instant is turned into
 * the bank's date, the month is added there, and the result becomes start-of-day in
 * {@link BankZone} again. Adding a fixed number of hours would be a different question with
 * a different answer, and the difference shows up as a payment creeping across midnight.
 *
 * <p>MONTH ENDS CLAMP RATHER THAN OVERFLOW. {@code LocalDate.plusMonths} takes 31 January
 * to 28 February, not to 3 March. An order set up on the 31st therefore pays on the last
 * day of a short month, which is what a bank does and what the customer expects.
 *
 * <p>KNOWN LIMITATION, WRITTEN DOWN RATHER THAN DISCOVERED LATER: the clamp is not
 * remembered. Because the next date is computed from the occurrence that just ran, an order
 * anchored on the 31st pays 28 February and then moves to 28 March -- the anchor day is
 * lost after the first short month and never comes back. Fixing it needs the anchor stored
 * on the row, which is a schema change rather than a change here.
 */
public enum StandingOrderFrequency {

    /**
     * Every two minutes.
     *
     * <p>FOR WATCHING THE UNATTENDED PATH WORK. Standing orders are the one way money leaves
     * an account in this system without a manager releasing it, which makes them the thing
     * most worth seeing before trusting -- and a monthly order shows that once a month. This
     * runs the identical code: same debit, same credit, same idempotency key, same
     * notifications, same advance, two minutes apart.
     *
     * <p>A TRUE DURATION, not a date hop, because two minutes is not a question about days
     * and has no calendar to consult.
     */
    EVERY_2_MINUTES {
        @Override
        public Instant next(Instant from) {
            return from.plus(Duration.ofMinutes(2));
        }
    },

    WEEKLY {
        @Override
        public Instant next(Instant from) {
            return BankZone.startOfDay(BankZone.dateOf(from).plusWeeks(1));
        }
    },

    MONTHLY {
        @Override
        public Instant next(Instant from) {
            return BankZone.startOfDay(BankZone.dateOf(from).plusMonths(1));
        }
    },

    QUARTERLY {
        @Override
        public Instant next(Instant from) {
            return BankZone.startOfDay(BankZone.dateOf(from).plusMonths(3));
        }
    };

    /** When the occurrence after {@code from} falls. */
    public abstract Instant next(Instant from);

    /**
     * Whether this period is shorter than a day, and so has no date to be "on".
     *
     * <p>Used where a schedule has to be shown to somebody: a monthly order is described by
     * its date, a two-minute order only makes sense as a time.
     */
    public boolean isSubDaily() {
        return this == EVERY_2_MINUTES;
    }
}
