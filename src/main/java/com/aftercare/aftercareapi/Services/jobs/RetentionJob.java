package com.aftercare.aftercareapi.Services.jobs;

import com.aftercare.aftercareapi.Enums.RetentionMode;
import com.aftercare.aftercareapi.Enums.RetentionTrigger;
import com.aftercare.aftercareapi.config.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled purge. Disabled by default; enabled with dry-run (the default) it only counts; real deletion needs
 * RETENTION_PURGE_ENABLED=true and RETENTION_DRY_RUN=false in addition to the periods an administrator sets.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService retention;
    private final RetentionProperties props;

    public RetentionJob(RetentionService retention, RetentionProperties props) {
        this.retention = retention;
        this.props = props;
    }

    public void runOnce() {
        if (!props.purge().enabled()) {
            return;
        }
        retention.run(props.purge().dryRun() ? RetentionMode.DRY_RUN : RetentionMode.PURGE, RetentionTrigger.SCHEDULED,
                RetentionService.SYSTEM);
    }

    @Component
    @ConditionalOnProperty(name = "aftercare.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Trigger {
        private final RetentionJob job;

        Trigger(RetentionJob job) {
            this.job = job;
        }

        @Scheduled(cron = "${aftercare.retention.purge.cron:0 15 3 * * *}", zone = "${aftercare.timezone:America/Toronto}")
        void tick() {
            try {
                job.runOnce();
            } catch (RuntimeException e) {
                log.error("Retention job failed ({})", e.getClass().getSimpleName());
            }
        }
    }
}
