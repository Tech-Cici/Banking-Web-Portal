package rw.bank.ibanking.onboarding.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import rw.bank.ibanking.exception.BusinessRuleException;

/**
 * Runs the due standing orders, one transaction each.
 *
 * <p>THIS EXISTS BECAUSE OF HOW SPRING TRANSACTIONS WORK, not because the work is
 * complicated. {@code StandingOrderService.runOne} is annotated REQUIRES_NEW so that one
 * order that cannot pay does not roll back the ones that already did. That annotation is
 * applied by a proxy, and a method calling its own sibling never passes through the proxy
 * -- so the loop has to live in a different bean or the isolation silently does not happen.
 * It is the kind of bug that shows up as "the sweep paid nothing and said it paid four".
 *
 * <p>A REFUSED PAYMENT IS RECORDED IN ITS OWN TRANSACTION TOO. By the time {@code runOne}
 * throws, its transaction is marked rollback-only; writing the failure there would roll it
 * back along with everything else and the customer would never be told their rent did not
 * go out.
 */
@Component
public class StandingOrderRunner {

    private static final Logger log = LoggerFactory.getLogger(StandingOrderRunner.class);

    private final StandingOrderService orders;

    StandingOrderRunner(StandingOrderService orders) {
        this.orders = orders;
    }

    /**
     * Pays everything due at or before {@code now}, and says how many paid.
     *
     * <p>AT OR BEFORE, not at. The host sleeps, and a sweep that only looked at this moment
     * would turn "monthly" into "the months the container happened to be awake".
     *
     * <p>ONE OCCURRENCE PER ORDER PER SWEEP, which is a real limit rather than a detail. An
     * order owing three periods pays one of them here and stays due; the next sweep pays the
     * next. It catches up at the rate the sweep runs, so an hourly timer takes three hours
     * to settle three missed months. Paying the whole backlog in one pass would mean several
     * debits landing in one second, each needing its own transaction to stay isolated -- a
     * worthwhile change, but a different one from this loop.
     */
    public int runDue(Instant now) {
        List<UUID> due = orders.dueBy(now);

        int paid = 0;
        for (UUID id : due) {
            try {
                orders.runOne(id);
                paid++;
            } catch (BusinessRuleException refused) {
                /*
                 * The expected failure: not enough money, a closed account. The message is
                 * the one the domain wrote, so the customer reads the real reason rather
                 * than a sentence this class invented about it.
                 */
                orders.recordFailure(id, refused.getMessage());
            } catch (RuntimeException unexpected) {
                /* Never let one bad row stop the sweep. */
                log.error("Standing order {} could not be processed", id, unexpected);
            }
        }
        return paid;
    }
}
