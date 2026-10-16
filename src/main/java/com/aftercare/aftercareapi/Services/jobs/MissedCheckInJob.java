package com.aftercare.aftercareapi.Services.jobs;

import com.aftercare.aftercareapi.Enums.ActivityKind;
import com.aftercare.aftercareapi.Enums.ActorType;
import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.AlertStatus;
import com.aftercare.aftercareapi.Enums.AlertType;
import com.aftercare.aftercareapi.Models.Alert;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Repositories.AlertRepository;
import com.aftercare.aftercareapi.Repositories.CheckInRepository;
import com.aftercare.aftercareapi.Repositories.FollowUpRepository;
import com.aftercare.aftercareapi.Services.ActivityService;
import com.aftercare.aftercareapi.Services.ClinicSettingsService;
import com.aftercare.aftercareapi.Services.Texts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates a MISSED_CHECKIN alert for each expected day without a check-in once the local cutoff has passed. Idempotent
 * (unique per follow-up and day), multi-instance safe (lease row plus the unique key) and limited to a two-day catch-up.
 */
@Component
public class MissedCheckInJob {

    public static final String LOCK = "missed-checkin";
    private static final Logger log = LoggerFactory.getLogger(MissedCheckInJob.class);

    private final JobLockService locks;
    private final ClinicSettingsService settings;
    private final FollowUpRepository followUps;
    private final CheckInRepository checkIns;
    private final AlertRepository alerts;
    private final ActivityService activity;
    private final Clock clock;
    private final TransactionTemplate newTx;
    @PersistenceContext
    private EntityManager em;

    public MissedCheckInJob(JobLockService locks, ClinicSettingsService settings, FollowUpRepository followUps,
                            CheckInRepository checkIns, AlertRepository alerts, ActivityService activity, Clock clock,
                            PlatformTransactionManager txManager) {
        this.locks = locks;
        this.settings = settings;
        this.followUps = followUps;
        this.checkIns = checkIns;
        this.alerts = alerts;
        this.activity = activity;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Returns the number of alerts created (0 when the lock is held elsewhere or the feature is off). */
    public int runOnce() {
        if (!locks.tryAcquire(LOCK, Duration.ofMinutes(10))) {
            return 0;
        }
        try {
            ClinicSettings cfg = settings.current();
            if (!cfg.isMissedCheckInEnabled()) {
                return 0;
            }
            ZonedDateTime now = ZonedDateTime.now(clock);
            ZoneId zone = clock.getZone();
            LocalDate today = now.toLocalDate();
            LocalDate lastDay = now.toLocalTime().isBefore(cfg.getMissedCheckInCutoff()) ? today.minusDays(1) : today;
            LocalDate firstDay = today.minusDays(2);
            List<ExpectedDays.Row> rows = followUps.activeEndingOnOrAfter(firstDay);
            if (rows.isEmpty()) {
                return 0;
            }
            List<Long> ids = rows.stream().map(ExpectedDays.Row::id).toList();
            Set<String> done = new HashSet<>();
            for (Object[] o : checkIns.dates(ids, firstDay.minusDays(1), lastDay)) {
                done.add(o[0] + "|" + o[1]);
            }
            Set<String> existing = new HashSet<>();
            for (Object[] o : alerts.missedKeys(ids, firstDay, lastDay)) {
                existing.add(o[0] + "|" + o[1]);
            }
            Instant at = now.toInstant();
            int created = 0;
            for (ExpectedDays.Row f : rows) {
                LocalDate from = max(firstDay, f.firstExpected(zone));
                LocalDate to = f.endDate().isBefore(lastDay) ? f.endDate() : lastDay;
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (done.contains(f.id() + "|" + d) || existing.contains(f.id() + "|" + d)) {
                        continue;
                    }
                    LocalDate prev = d.minusDays(1);
                    boolean high = f.isExpected(prev, zone) && !done.contains(f.id() + "|" + prev);
                    if (create(f, d, high, at)) {
                        created++;
                    }
                }
            }
            log.info("Missed check-in job created {} alerts", created);
            return created;
        } finally {
            locks.release(LOCK);
        }
    }

    private boolean create(ExpectedDays.Row f, LocalDate d, boolean high, Instant at) {
        AlertSeverity severity = high ? AlertSeverity.HIGH : AlertSeverity.MEDIUM;
        String en = high ? "No check-in received on " + d.minusDays(1) + " and " + d
                : "No check-in received on " + d;
        String fr = high ? "Aucun suivi reçu le " + d.minusDays(1) + " ni le " + d
                : "Aucun suivi reçu le " + d;
        try {
            Boolean made = newTx.execute(status -> {
                if (checkIns.existsByFollowUpIdAndCheckInDate(f.id(), d)) {
                    return false; // a check-in arrived since the day's data was read
                }
                Alert saved = alerts.saveAndFlush(Alert.builder()
                        .patient(em.getReference(Patient.class, f.patientId()))
                        .followUp(em.getReference(FollowUp.class, f.id()))
                        .type(AlertType.MISSED_CHECKIN)
                        .missedDate(d)
                        .severity(severity)
                        .messageEn(en)
                        .messageFr(fr)
                        .status(AlertStatus.OPEN)
                        .createdAt(at)
                        .build());
                activity.record(f.patientId(), ActivityKind.ALERT_CREATED, at, ActorType.SYSTEM, null, null, saved.getId(),
                        severity, Texts.humanize(severity) + " alert: " + en);
                return true;
            });
            return Boolean.TRUE.equals(made);
        } catch (DataIntegrityViolationException raced) {
            // Another instance created the same (follow-up, day) alert first.
            return false;
        }
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** The scheduled trigger; absent when jobs are disabled. */
    @Component
    @ConditionalOnProperty(name = "aftercare.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Trigger {
        private final MissedCheckInJob job;

        Trigger(MissedCheckInJob job) {
            this.job = job;
        }

        @Scheduled(cron = "0 */15 * * * *", zone = "${aftercare.timezone:America/Toronto}")
        void tick() {
            try {
                job.runOnce();
            } catch (RuntimeException e) {
                log.error("Missed check-in job failed ({})", e.getClass().getSimpleName());
            }
        }
    }
}
