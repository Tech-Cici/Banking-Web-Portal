package rw.bank.ibanking.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The time zone the bank's days are measured in.
 *
 * <p>WHY THIS EXISTS AT ALL. A schedule that says "the 5th" has to mean one moment, and
 * which moment depends entirely on whose day you are counting. The server's default zone is
 * not an answer: it is a deployment detail that changes when the container moves, and a
 * payment that silently lands a day early because a host was reconfigured is the kind of
 * fault nobody traces back to a missing {@code ZoneId}. The bank is in Kigali, its customers
 * are in Kigali, so the bank's day is Kigali's day no matter where the process runs.
 *
 * <p>KIGALI HAS NO DAYLIGHT SAVING. Central Africa Time is UTC+2 the whole year, which is
 * why start-of-day here is a single unambiguous instant rather than the two-or-none that a
 * clock change produces twice a year elsewhere. Any future zone with daylight saving would
 * need {@link LocalDate#atStartOfDay(ZoneId)} examined again -- it resolves gaps by moving
 * forward, which is right, but it is a decision rather than an identity.
 *
 * <p>ONE PLACE, so that the conversion cannot drift. Everything storing a date-shaped
 * schedule as an instant converts through here.
 */
public final class BankZone {

    /** Africa/Kigali: UTC+2, no daylight saving. */
    public static final ZoneId ID = ZoneId.of("Africa/Kigali");

    private BankZone() {
        // Constants only.
    }

    /** The first instant of {@code date} as the bank's customers experience that day. */
    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ID).toInstant();
    }

    /** Which of the bank's days {@code instant} falls on. */
    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(ID).toLocalDate();
    }

    /** Today, as the bank reckons it. */
    public static LocalDate today() {
        return LocalDate.now(ID);
    }
}
