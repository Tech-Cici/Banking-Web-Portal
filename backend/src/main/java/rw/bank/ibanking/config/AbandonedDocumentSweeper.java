package rw.bank.ibanking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rw.bank.ibanking.collateral.CollateralService;

/**
 * Deletes collateral documents that were uploaded and never submitted with an application.
 *
 * <p>WHY THIS EXISTS AT ALL. Somebody photographs their land title, starts an application,
 * changes their mind and closes the tab. Without this, a scan of their property sits in the
 * bank's database for good: attached to nothing, serving nobody, and waiting to turn up in a
 * breach. The sweep is not housekeeping, it is the difference between keeping a document
 * because an application needs it and keeping one because nothing deleted it.
 *
 * <p>HOURLY, AND ALSO ON STARTUP, for the reason the standing-order sweep gives: a host that
 * sleeps runs no cron, and a retention rule that only applies while the service happens to be
 * awake is not a retention rule. The cut-off is twenty-four hours, which is long enough for
 * somebody to be interrupted mid-application and come back to it.
 *
 * <p>NOT UNDER THE TEST PROFILE. A timer deleting rows in the background of an integration
 * suite would remove another test's fixture at a moment nothing controls.
 */
@Component
@Profile("!test")
public class AbandonedDocumentSweeper {

    private static final Logger log = LoggerFactory.getLogger(AbandonedDocumentSweeper.class);

    private final CollateralService collateral;

    AbandonedDocumentSweeper(CollateralService collateral) {
        this.collateral = collateral;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep("startup");
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void hourly() {
        sweep("timer");
    }

    /**
     * Never lets a failure escape.
     *
     * <p>A SWEEP THAT THROWS ON THE SCHEDULER'S THREAD STOPS BEING SCHEDULED, silently, for
     * the life of the process -- and the thing that stops is the only thing deleting scans of
     * people's property. An exception here is logged and the next hour tries again.
     */
    private void sweep(String trigger) {
        try {
            int deleted = collateral.sweepAbandoned();
            if (deleted > 0) {
                log.info("Swept {} abandoned collateral upload(s) ({})", deleted, trigger);
            }
        } catch (RuntimeException failure) {
            log.error("The abandoned-document sweep ({}) failed", trigger, failure);
        }
    }
}
