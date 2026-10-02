package rw.bank.ibanking.onboarding.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import rw.bank.ibanking.exception.RateLimitedException;

/**
 * HOW OFTEN ONE CUSTOMER MAY ASK WHO HOLDS AN ACCOUNT NUMBER.
 *
 * <p>WHY THIS EXISTS. {@code /transfers/resolve} answers a full account number with the
 * holder's name, so somebody can catch a mistyped digit before their money goes to a
 * stranger. The same answer, asked ten thousand times, is an identity harvest: account
 * numbers are short and structured, and a list of them becomes a list of the people behind
 * them. The lookup is worth having and is only worth having bounded.
 *
 * <p>The bound is per SIGNED-IN CUSTOMER, not per IP. An IP is shared by everyone behind
 * one office connection and changed freely by anybody who wants to, so limiting on it
 * punishes colleagues and stops nobody. A customer id costs an approved bank account to
 * obtain.
 *
 * <p>IN MEMORY, AND THAT IS A REAL LIMIT — stated rather than glossed. The counters live in
 * this process, so they reset when the service restarts and are not shared between
 * instances: two instances behind a load balancer allow twice the traffic, and a restart
 * clears everybody's history. That is enough to turn a scripted harvest into a slow, noisy
 * one, and not enough to be the only thing standing in its way. The durable version is a
 * counter in the database or in Redis, keyed the same way; it is recorded in
 * docs/OPEN-ITEMS.md.
 *
 * <p>Every refusal is logged, because a customer who hits this limit is doing something no
 * ordinary customer does.
 */
@Service
public class BeneficiaryLookups {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryLookups.class);

    /**
     * How many lookups one customer may make in {@link #WINDOW}.
     *
     * <p>Twenty, because a person paying somebody checks one number, occasionally
     * mistypes it and checks again, and might do that for a handful of payees in a
     * sitting. Twenty is comfortably above that and far below anything useful for
     * walking a number range.
     */
    private static final int LIMIT = 20;

    private static final Duration WINDOW = Duration.ofHours(1);

    /**
     * When each customer's recent lookups happened.
     *
     * <p>A sliding window rather than a fixed hourly bucket: a fixed bucket lets somebody
     * spend the whole allowance at 10:59 and the whole next allowance at 11:01, which is
     * twice the intended rate at exactly the moment somebody is trying.
     */
    private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

    /**
     * Records one lookup, or refuses it.
     *
     * @throws RateLimitedException when this customer has had {@link #LIMIT} in the last
     *     hour. The message says when to come back, because a customer who has genuinely
     *     been checking payees should not be left guessing.
     */
    public void record(UUID customerId) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);

        /*
         * compute, so the read-modify-write happens under the map's per-key lock. A
         * get-then-put would let two requests from the same customer each read a count of
         * 19 and both proceed — which on a limit is the one case that matters.
         */
        Duration[] retryAfter = new Duration[1];

        recent.compute(
                customerId,
                (id, times) -> {
                    Deque<Instant> window = times == null ? new ArrayDeque<>() : times;

                    // Drop anything older than the window before counting.
                    while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                        window.pollFirst();
                    }

                    if (window.size() >= LIMIT) {
                        /*
                         * When the oldest lookup in the window falls out of it, which is
                         * the moment one more becomes available.
                         */
                        retryAfter[0] = Duration.between(cutoff, window.peekFirst());
                        return window;
                    }

                    window.addLast(now);
                    return window;
                });

        if (retryAfter[0] != null) {
            long minutes = Math.max(1, retryAfter[0].toMinutes());
            log.warn(
                    "Customer {} hit the beneficiary lookup limit ({} in the last hour)",
                    customerId,
                    LIMIT);
            throw new RateLimitedException(
                    "You have checked a lot of account numbers recently. Please try again in "
                            + minutes
                            + (minutes == 1 ? " minute." : " minutes."),
                    retryAfter[0]);
        }
    }

    /**
     * Forgets everything. For tests only.
     *
     * <p>Without it the limit leaks between tests in the same JVM: a test that makes
     * twenty lookups makes the next test's first one fail, and which test that is depends
     * on the order they happen to run in.
     *
     * <p>Public because the test base class lives in another package, and there is no
     * harm in it being callable: clearing the counters cannot grant access to anything —
     * the worst it does is allow twenty more lookups, which a restart does anyway.
     */
    public void reset() {
        recent.clear();
    }
}
