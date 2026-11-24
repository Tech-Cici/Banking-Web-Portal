package rw.bank.ibanking.config;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rw.bank.ibanking.onboarding.service.StandingOrderRunner;

/**
 * Wakes the standing-order sweep.
 *
 * <p>NOT UNDER THE TEST PROFILE, and that is a correctness requirement rather than tidiness.
 * These payments move money with nobody approving them; a timer doing that in the
 * background of an integration suite would alter other tests' balances at moments nothing
 * controls, and the failures would surface anywhere but here. The logic lives in {@link
 * StandingOrderRunner#runDue}, which a test calls directly with a date it chose. Only the
 * clock is conditional.
 *
 * <p>IT ALSO SWEEPS ON STARTUP, because the host sleeps. Render's free tier stops the
 * container after about fifteen minutes idle, and a cron inside a stopped container does
 * not fire -- so a timer alone would quietly turn "monthly" into "the months the service
 * happened to be awake". {@code runDue} pays everything due AT OR BEFORE now, one occurrence
 * per order per sweep, so a long outage settles over the following sweeps rather than in the
 * first one.
 *
 * <p>THE INTERVAL IS CONFIGURATION, NOT A CONSTANT, and the reason is the two-minute
 * frequency. A timer that fires hourly cannot pay something due every two minutes on time:
 * the order would be correct in the database and invisible in practice, which is the worst
 * of both. Production keeps the hourly default -- nothing there is due at finer resolution
 * than a day, and sweeping more often is work with no payment at the end of it. Development
 * sets it to seconds so the unattended path can be watched instead of taken on trust.
 */
@Component
@Profile("!test")
@EnableScheduling
public class StandingOrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(StandingOrderScheduler.class);

    private final StandingOrderRunner runner;

    StandingOrderScheduler(StandingOrderRunner runner) {
        this.runner = runner;
    }

    /** Catches up whatever fell due while the container was stopped. */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep("startup");
    }

    /**
     * And on the configured interval while it is awake.
     *
     * <p>fixedDelay RATHER THAN fixedRate: the gap is measured from the end of one sweep to
     * the start of the next, so a slow sweep delays the following one instead of having it
     * queue up behind. Two sweeps overlapping would both see the same order as due, and the
     * only thing standing between that and a double payment is the ledger's idempotency
     * key -- a guard worth having and not worth leaning on.
     */
    @Scheduled(
            fixedDelayString = "${ibanking.standing-orders.sweep-interval-ms:3600000}",
            initialDelayString = "${ibanking.standing-orders.sweep-interval-ms:3600000}")
    public void sweepOnTimer() {
        sweep("timer");
    }

    private void sweep(String trigger) {
        try {
            int ran = runner.runDue(Instant.now());
            if (ran > 0) log.info("Standing order sweep ({}) paid {} order(s)", trigger, ran);
        } catch (RuntimeException failure) {
            /* A sweep that throws must not take the application down with it. */
            log.error("Standing order sweep ({}) failed", trigger, failure);
        }
    }
}
