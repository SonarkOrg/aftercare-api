package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.AppointmentStatus;
import com.aftercare.aftercareapi.Enums.NotificationTemplate;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Repositories.AppointmentIntakeRepository;
import com.aftercare.aftercareapi.Repositories.AppointmentRepository;
import com.aftercare.aftercareapi.Services.ClinicSettingsService;
import com.aftercare.aftercareapi.Services.jobs.JobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/**
 * Reminds patients whose pre-appointment questionnaire is still empty when the appointment is within the configured
 * window. Appointments booked inside the window are skipped (the link message was just sent).
 */
@Component
public class AppointmentReminderJob {

    public static final String LOCK = "appointment-reminder";
    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderJob.class);

    private final JobLockService locks;
    private final ClinicSettingsService settings;
    private final AppointmentRepository appointments;
    private final AppointmentIntakeRepository intakes;
    private final NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate readTx;

    public AppointmentReminderJob(JobLockService locks, ClinicSettingsService settings, AppointmentRepository appointments,
                                  AppointmentIntakeRepository intakes, NotificationService notifications, Clock clock,
                                  PlatformTransactionManager txManager) {
        this.locks = locks;
        this.settings = settings;
        this.appointments = appointments;
        this.intakes = intakes;
        this.notifications = notifications;
        this.clock = clock;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /** Returns the number of reminders enqueued. */
    public int runOnce() {
        if (!locks.tryAcquire(LOCK, Duration.ofMinutes(10))) {
            return 0;
        }
        try {
            ClinicSettings cfg = settings.current();
            if (!cfg.isAppointmentReminderEnabled()) {
                return 0;
            }
            Instant now = Instant.now(clock);
            Duration window = Duration.ofHours(cfg.getAppointmentReminderHours());
            List<Object[]> candidates = readTx.execute(s -> appointments.reminderCandidates(
                    EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.READY, AppointmentStatus.NEEDS_REVIEW),
                    now, now.plus(window)));
            int enqueued = 0;
            for (Object[] c : candidates) {
                Long id = (Long) c[0];
                Long patientId = (Long) c[1];
                Instant scheduledAt = (Instant) c[2];
                Instant createdAt = (Instant) c[3];
                if (createdAt.isAfter(scheduledAt.minus(window)) || intakes.existsByAppointmentId(id)) {
                    continue;
                }
                if (notifications.enqueueAutomated(NotificationTemplate.APPOINTMENT_REMINDER, patientId, null, id,
                        "APPOINTMENT_REMINDER:" + id)) {
                    enqueued++;
                }
            }
            log.info("Appointment reminder job enqueued {} notifications", enqueued);
            return enqueued;
        } finally {
            locks.release(LOCK);
        }
    }

    @Component
    @ConditionalOnProperty(name = "aftercare.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Trigger {
        private final AppointmentReminderJob job;

        Trigger(AppointmentReminderJob job) {
            this.job = job;
        }

        @Scheduled(cron = "0 */15 * * * *", zone = "${aftercare.timezone:America/Toronto}")
        void tick() {
            try {
                job.runOnce();
            } catch (RuntimeException e) {
                log.error("Appointment reminder job failed ({})", e.getClass().getSimpleName());
            }
        }
    }
}
