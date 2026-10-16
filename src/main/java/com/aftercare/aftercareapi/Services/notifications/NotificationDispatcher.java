package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Appointment;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Models.NotificationOutbox;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Repositories.AppointmentIntakeRepository;
import com.aftercare.aftercareapi.Repositories.AppointmentRepository;
import com.aftercare.aftercareapi.Repositories.CheckInRepository;
import com.aftercare.aftercareapi.Repositories.FollowUpRepository;
import com.aftercare.aftercareapi.Repositories.NotificationOutboxRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import com.aftercare.aftercareapi.Services.AccessLinkService;
import com.aftercare.aftercareapi.Services.AppointmentService;
import com.aftercare.aftercareapi.Services.ClinicSettingsService;
import com.aftercare.aftercareapi.Services.FollowUpService;
import com.aftercare.aftercareapi.Services.jobs.JobLockService;
import com.aftercare.aftercareapi.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Sends PENDING outbox rows. Re-checks consent, channel, recipient and applicability right before sending, renders the
 * message from live data (the outbox holds no body or link), retries transient failures with backoff and records only
 * statuses, counts and masked recipients. A crash mid-send may produce one duplicate (documented).
 */
@Component
public class NotificationDispatcher {

    public static final String LOCK = "notification-dispatch";
    static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6)};
    static final int MAX_ATTEMPTS = 5;
    private static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    /** The dispatcher lease; a batch stops well before it expires so two instances never send concurrently. */
    static final Duration LEASE = Duration.ofMinutes(10);
    static final Duration LEASE_MARGIN = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** Counts of one tick. */
    public record Tick(int sent, int retried, int failed, int skipped) {
    }

    private final JobLockService locks;
    private final NotificationOutboxRepository outbox;
    private final PatientRepository patients;
    private final FollowUpRepository followUps;
    private final AppointmentRepository appointments;
    private final AppointmentIntakeRepository intakes;
    private final CheckInRepository checkIns;
    private final AccessLinkService links;
    private final ClinicSettingsService settings;
    private final SenderRegistry senders;
    private final NotificationRenderer renderer;
    private final NotificationProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public NotificationDispatcher(JobLockService locks, NotificationOutboxRepository outbox, PatientRepository patients,
                                  FollowUpRepository followUps, AppointmentRepository appointments,
                                  AppointmentIntakeRepository intakes, CheckInRepository checkIns, AccessLinkService links,
                                  ClinicSettingsService settings, SenderRegistry senders, NotificationRenderer renderer,
                                  NotificationProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.locks = locks;
        this.outbox = outbox;
        this.patients = patients;
        this.followUps = followUps;
        this.appointments = appointments;
        this.intakes = intakes;
        this.checkIns = checkIns;
        this.links = links;
        this.settings = settings;
        this.senders = senders;
        this.renderer = renderer;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Tick runOnce() {
        if (!locks.tryAcquire(LOCK, LEASE)) {
            return new Tick(0, 0, 0, 0);
        }
        try {
            return dispatch();
        } finally {
            locks.release(LOCK);
        }
    }

    private Tick dispatch() {
        Instant now = Instant.now(clock);
        // 1. recovery of rows stuck in SENDING (a crash mid-send)
        for (NotificationOutbox stuck : outbox.findByStatusAndUpdatedAtBefore(NotificationStatus.SENDING, now.minus(STUCK_AFTER))) {
            update(stuck.getId(), n -> {
                if (n.getAttempts() >= MAX_ATTEMPTS) {
                    n.setStatus(NotificationStatus.FAILED);
                    n.setLastError(NotificationError.PROVIDER_ERROR);
                } else {
                    n.setStatus(NotificationStatus.PENDING);
                    n.setNextAttemptAt(now);
                }
            });
        }
        // 2. hourly cap
        long sentLastHour = outbox.countByStatusAndSentAtAfter(NotificationStatus.SENT, now.minus(Duration.ofHours(1)));
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int skipped = 0;
        if (sentLastHour >= props.maxPerHour()) {
            log.warn("Notification hourly cap reached");
            return new Tick(0, 0, 0, 0);
        }
        List<NotificationOutbox> batch = outbox.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                NotificationStatus.PENDING, now, PageRequest.of(0, props.batchSize()));
        Instant stopAt = now.plus(LEASE).minus(LEASE_MARGIN);
        for (NotificationOutbox candidate : batch) {
            if (!Instant.now(clock).isBefore(stopAt)) {
                break; // the lease is about to expire: the next tick continues
            }
            if (sentLastHour + sent >= props.maxPerHour()) {
                log.warn("Notification hourly cap reached");
                break;
            }
            Outcome o = process(candidate.getId());
            switch (o) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case DEFERRED -> { }
            }
        }
        if (sent + retried + failed + skipped > 0) {
            log.info("Dispatched {} sent, {} retried, {} failed, {} skipped", sent, retried, failed, skipped);
        }
        return new Tick(sent, retried, failed, skipped);
    }

    private enum Outcome { SENT, RETRY, FAILED, SKIPPED, DEFERRED }

    private Outcome process(Long id) {
        ZonedDateTime local = ZonedDateTime.now(clock);
        Instant now = local.toInstant();
        NotificationOutbox fresh = outbox.findById(id).orElse(null);
        if (fresh == null || fresh.getStatus() != NotificationStatus.PENDING) {
            return Outcome.DEFERRED;
        }
        // Quiet hours apply to automated sends only.
        if (fresh.isAutomated() && quiet(local.toLocalTime())) {
            Instant next = nextQuietEnd(local);
            update(id, n -> n.setNextAttemptAt(next));
            return Outcome.DEFERRED;
        }
        Integer claimed = tx.execute(status -> outbox.claim(id, Instant.now(clock)));
        if (claimed == null || claimed == 0) {
            return Outcome.DEFERRED; // someone else got it (or it is no longer PENDING)
        }
        NotificationOutbox row = outbox.findById(id).orElseThrow();

        // ---- re-checks, outside any transaction
        Patient patient = patients.findById(row.getPatientId()).orElse(null);
        if (patient == null) {
            return finish(id, NotificationStatus.SKIPPED, NotificationError.NOT_APPLICABLE, null, Outcome.SKIPPED);
        }
        ClinicSettings cfg = settings.current();
        if (patient.getContactConsent() != ContactConsent.GRANTED) {
            return finish(id, NotificationStatus.CANCELLED, NotificationError.NO_CONSENT, null, Outcome.FAILED);
        }
        boolean enabled = row.getChannel() == NotificationChannel.EMAIL ? cfg.isEmailEnabled() : cfg.isSmsEnabled();
        if (!enabled) {
            return finish(id, NotificationStatus.CANCELLED, NotificationError.CHANNEL_DISABLED, null, Outcome.FAILED);
        }
        Optional<String> to = Recipients.forChannel(row.getChannel(), patient);
        if (to.isEmpty()) {
            return finish(id, NotificationStatus.FAILED, NotificationError.INVALID_RECIPIENT, null, Outcome.FAILED);
        }
        String masked = Recipients.mask(row.getChannel(), to.get());
        if (notApplicable(row, now)) {
            return finish(id, NotificationStatus.SKIPPED, NotificationError.NOT_APPLICABLE, masked, Outcome.SKIPPED);
        }
        Optional<String> code = currentCode(row);
        if (code.isEmpty()) {
            return finish(id, NotificationStatus.FAILED, NotificationError.LINK_UNAVAILABLE, masked, Outcome.FAILED);
        }

        // ---- render and send
        SendResult result;
        try {
            NotificationTemplates.Rendered msg = renderer.render(row, patient, cfg, code.get());
            result = senders.forChannel(row.getChannel()).send(new OutboundMessage(row.getChannel(), to.get(),
                    msg.subject(), msg.body(), row.getId(), row.getTemplate().name(), masked));
        } catch (RuntimeException e) {
            result = new SendResult.Transient("EXCEPTION");
        }
        int attempts = row.getAttempts();
        Instant at = Instant.now(clock);
        if (result instanceof SendResult.Sent sent) {
            String providerId = sent.providerId() == null ? null
                    : (sent.providerId().length() > 64 ? sent.providerId().substring(0, 64) : sent.providerId());
            update(id, n -> {
                n.setStatus(NotificationStatus.SENT);
                n.setSentAt(at);
                n.setLastError(null);
                n.setRecipientMasked(masked);
                n.setProviderMessageId(providerId);
            });
            return Outcome.SENT;
        }
        if (result instanceof SendResult.Permanent) {
            return finish(id, NotificationStatus.FAILED, NotificationError.PROVIDER_REJECTED, masked, Outcome.FAILED);
        }
        if (attempts >= MAX_ATTEMPTS) {
            return finish(id, NotificationStatus.FAILED, NotificationError.PROVIDER_ERROR, masked, Outcome.FAILED);
        }
        Instant next = at.plus(BACKOFF[attempts - 1]);
        update(id, n -> {
            n.setStatus(NotificationStatus.PENDING);
            n.setLastError(NotificationError.PROVIDER_ERROR);
            n.setRecipientMasked(masked);
            n.setNextAttemptAt(next);
        });
        return Outcome.RETRY;
    }

    private Outcome finish(Long id, NotificationStatus status, NotificationError error, String masked, Outcome outcome) {
        update(id, n -> {
            n.setStatus(status);
            n.setLastError(error);
            if (masked != null) {
                n.setRecipientMasked(masked);
            }
        });
        return outcome;
    }

    /** The target is gone or the message no longer makes sense. */
    private boolean notApplicable(NotificationOutbox row, Instant now) {
        LocalDate today = LocalDate.now(clock);
        if (row.getFollowUpId() != null) {
            FollowUp f = followUps.findById(row.getFollowUpId()).orElse(null);
            if (f == null || !FollowUpService.linkUsable(f, today)) {
                return true;
            }
        } else if (row.getAppointmentId() != null) {
            Appointment a = appointments.findById(row.getAppointmentId()).orElse(null);
            if (a == null || !AppointmentService.linkUsable(a, now)) {
                return true;
            }
        } else {
            return true;
        }
        if (row.getTemplate() == NotificationTemplate.CHECKIN_REMINDER) {
            if (checkIns.existsByFollowUpIdAndCheckInDate(row.getFollowUpId(), today)) {
                return true;
            }
            String key = row.getDedupeKey();
            if (key != null && !key.endsWith(":" + today)) {
                return true;
            }
        }
        return row.getTemplate() == NotificationTemplate.APPOINTMENT_REMINDER && row.getAppointmentId() != null
                && intakes.existsByAppointmentId(row.getAppointmentId());
    }

    private Optional<String> currentCode(NotificationOutbox row) {
        try {
            return row.getFollowUpId() != null ? links.currentCodeForFollowUp(row.getFollowUpId())
                    : links.currentCodeForAppointment(row.getAppointmentId());
        } catch (IllegalStateException undecryptable) {
            return Optional.empty();
        }
    }

    private boolean quiet(LocalTime t) {
        LocalTime start = props.quietHours().startTime();
        LocalTime end = props.quietHours().endTime();
        return start.isAfter(end) ? !t.isBefore(start) || t.isBefore(end) : !t.isBefore(start) && t.isBefore(end);
    }

    /** The next moment quiet hours end (08:00 local by default). */
    private Instant nextQuietEnd(ZonedDateTime local) {
        LocalTime start = props.quietHours().startTime();
        LocalTime end = props.quietHours().endTime();
        LocalDate day = local.toLocalDate();
        if (start.isAfter(end) && !local.toLocalTime().isBefore(start)) {
            day = day.plusDays(1);
        }
        return day.atTime(end).atZone(clock.getZone()).toInstant();
    }

    private NotificationOutbox update(Long id, Consumer<NotificationOutbox> change) {
        return tx.execute(status -> {
            NotificationOutbox n = outbox.findById(id).orElseThrow();
            change.accept(n);
            n.setUpdatedAt(Instant.now(clock));
            return outbox.saveAndFlush(n);
        });
    }

    /** The scheduled trigger; absent when jobs are disabled. */
    @Component
    @ConditionalOnProperty(name = "aftercare.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Trigger {
        private final NotificationDispatcher dispatcher;

        Trigger(NotificationDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Scheduled(fixedDelayString = "${aftercare.notifications.dispatch-interval:PT30S}")
        void tick() {
            try {
                dispatcher.runOnce();
            } catch (RuntimeException e) {
                log.error("Notification dispatch failed ({})", e.getClass().getSimpleName());
            }
        }
    }
}
