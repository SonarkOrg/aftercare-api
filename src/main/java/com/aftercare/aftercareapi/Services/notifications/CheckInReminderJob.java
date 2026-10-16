package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationTemplate;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Repositories.CheckInRepository;
import com.aftercare.aftercareapi.Repositories.FollowUpRepository;
import com.aftercare.aftercareapi.Services.ClinicSettingsService;
import com.aftercare.aftercareapi.Services.jobs.ExpectedDays;
import com.aftercare.aftercareapi.Services.jobs.JobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Daily reminder for follow-ups with an expected check-in today that has not arrived, between reminder time and cutoff. */
@Component
public class CheckInReminderJob {

    public static final String LOCK = "checkin-reminder";
    private static final Logger log = LoggerFactory.getLogger(CheckInReminderJob.class);

    private final JobLockService locks;
    private final ClinicSettingsService settings;
    private final FollowUpRepository followUps;
    private final CheckInRepository checkIns;
    private final NotificationService notifications;
    private final Clock clock;

    public CheckInReminderJob(JobLockService locks, ClinicSettingsService settings, FollowUpRepository followUps,
                              CheckInRepository checkIns, NotificationService notifications, Clock clock) {
        this.locks = locks;
        this.settings = settings;
        this.followUps = followUps;
        this.checkIns = checkIns;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Returns the number of reminders enqueued. */
    public int runOnce() {
        if (!locks.tryAcquire(LOCK, Duration.ofMinutes(10))) {
            return 0;
        }
        try {
            ClinicSettings cfg = settings.current();
            ZonedDateTime now = ZonedDateTime.now(clock);
            LocalTime time = now.toLocalTime();
            if (!cfg.isCheckInReminderEnabled() || time.isBefore(cfg.getCheckInReminderTime())
                    || !time.isBefore(cfg.getMissedCheckInCutoff())) {
                return 0;
            }
            ZoneId zone = clock.getZone();
            LocalDate today = now.toLocalDate();
            List<ExpectedDays.Row> rows = followUps.activeEndingOnOrAfter(today).stream()
                    .filter(r -> r.isExpected(today, zone)).toList();
            if (rows.isEmpty()) {
                return 0;
            }
            Set<Long> done = new HashSet<>();
            for (Object[] o : checkIns.dates(rows.stream().map(ExpectedDays.Row::id).toList(), today, today)) {
                done.add((Long) o[0]);
            }
            int enqueued = 0;
            for (ExpectedDays.Row r : rows) {
                if (!done.contains(r.id()) && notifications.enqueueAutomated(NotificationTemplate.CHECKIN_REMINDER,
                        r.patientId(), r.id(), null, "CHECKIN_REMINDER:" + r.id() + ":" + today)) {
                    enqueued++;
                }
            }
            log.info("Check-in reminder job enqueued {} notifications", enqueued);
            return enqueued;
        } finally {
            locks.release(LOCK);
        }
    }

    @Component
    @ConditionalOnProperty(name = "aftercare.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Trigger {
        private final CheckInReminderJob job;

        Trigger(CheckInReminderJob job) {
            this.job = job;
        }

        @Scheduled(cron = "0 */10 * * * *", zone = "${aftercare.timezone:America/Toronto}")
        void tick() {
            try {
                job.runOnce();
            } catch (RuntimeException e) {
                log.error("Check-in reminder job failed ({})", e.getClass().getSimpleName());
            }
        }
    }
}
