package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Models.Dto.NotificationDTO;
import com.aftercare.aftercareapi.Models.Dto.PageResponse;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Models.Appointment;
import com.aftercare.aftercareapi.Models.NotificationOutbox;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Repositories.AppointmentRepository;
import com.aftercare.aftercareapi.Repositories.FollowUpRepository;
import com.aftercare.aftercareapi.Repositories.NotificationOutboxRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import com.aftercare.aftercareapi.Services.AccessLinkService;
import com.aftercare.aftercareapi.Services.Actor;
import com.aftercare.aftercareapi.Services.AppointmentService;
import com.aftercare.aftercareapi.Services.AuditService;
import com.aftercare.aftercareapi.Services.ClinicSettingsService;
import com.aftercare.aftercareapi.Services.FollowUpService;
import com.aftercare.aftercareapi.config.NotificationProperties;
import com.aftercare.aftercareapi.config.security.CurrentStaff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Enqueues patient notifications (manual "Send link" and automated triggers) and lists them. The outbox stores
 * references only: no body, no link, no code. Consent, channel, recipient and link availability gate every enqueue.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final String SYSTEM_SUB = "system:notifications";
    private static final Set<NotificationStatus> COUNTED = EnumSet.of(NotificationStatus.PENDING,
            NotificationStatus.SENDING, NotificationStatus.SENT);
    private static final Set<NotificationTemplate> APPOINTMENT_TEMPLATES =
            EnumSet.of(NotificationTemplate.APPOINTMENT_LINK, NotificationTemplate.APPOINTMENT_REMINDER);

    /** An automated enqueue that is simply not applicable (no consent, channel off, ...): never an error. */
    private static final class Skip extends RuntimeException {
        Skip() {
            super(null, null, false, false);
        }
    }

    private final NotificationOutboxRepository outbox;
    private final PatientRepository patients;
    private final FollowUpRepository followUps;
    private final AppointmentRepository appointments;
    private final AccessLinkService links;
    private final ClinicSettingsService settings;
    private final AuditService audit;
    private final NotificationProperties props;
    private final Clock clock;
    private final TransactionTemplate newTx;

    public NotificationService(NotificationOutboxRepository outbox, PatientRepository patients, FollowUpRepository followUps,
                               AppointmentRepository appointments, AccessLinkService links, ClinicSettingsService settings,
                               AuditService audit, NotificationProperties props, Clock clock,
                               PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.patients = patients;
        this.followUps = followUps;
        this.appointments = appointments;
        this.links = links;
        this.settings = settings;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- manual

    /** Staff "Send link" for a follow-up. Returns the PENDING row; the dispatcher sends it. */
    @Transactional
    public NotificationDTO sendFollowUpLink(Long followUpId, NotificationChannel channel, Actor actor) {
        FollowUp f = followUps.findByIdForUpdate(followUpId).orElseThrow(() -> ApiException.notFound("Follow-up not found")); // locks the target: the manual cap below is then atomic
        if (FollowUpService.displayStatus(f, LocalDate.now(clock)) != FollowUpDisplayStatus.ACTIVE) {
            throw ApiException.invalidState("Follow-up is not active");
        }
        Patient p = f.getPatient();
        return toDto(enqueue(p, NotificationTemplate.FOLLOW_UP_LINK, f.getId(), null, channel, false, null, actor));
    }

    /** Staff "Send link" for an appointment questionnaire. */
    @Transactional
    public NotificationDTO sendAppointmentLink(Long appointmentId, NotificationChannel channel, Actor actor) {
        Appointment a = appointments.findByIdForUpdate(appointmentId).orElseThrow(() -> ApiException.notFound("Appointment not found")); // locks the target: the manual cap below is then atomic
        if (!AppointmentService.linkUsable(a, Instant.now(clock))) {
            throw ApiException.invalidState("Appointment link is not usable");
        }
        return toDto(enqueue(a.getPatient(), NotificationTemplate.APPOINTMENT_LINK, null, a.getId(), channel, false, null, actor));
    }

    // ---- automated

    /**
     * Enqueues without ever throwing into the caller: a missing consent, a disabled channel, no recipient, no stored
     * link, a volume cap or an already queued dedupe key simply mean "nothing to send". Runs in its own transaction.
     */
    public boolean enqueueAutomated(NotificationTemplate template, long patientId, Long followUpId, Long appointmentId,
                                    String dedupeKey) {
        try {
            Boolean created = newTx.execute(status -> {
                Patient p = patients.findById(patientId).orElse(null);
                if (p == null) {
                    return false;
                }
                try {
                    enqueue(p, template, followUpId, appointmentId, null, true, dedupeKey, null);
                    outbox.flush();
                    return true;
                } catch (Skip skip) {
                    return false;
                }
            });
            return Boolean.TRUE.equals(created);
        } catch (DataIntegrityViolationException duplicate) {
            return false; // the dedupe key was already enqueued
        } catch (RuntimeException e) {
            log.debug("Automated notification not enqueued ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    // ---- core

    private NotificationOutbox enqueue(Patient p, NotificationTemplate template, Long followUpId, Long appointmentId,
                                       NotificationChannel requested, boolean automated, String dedupeKey, Actor actor) {
        NotificationChannel channel = requested != null ? requested
                : (p.getPreferredChannel() == null ? NotificationChannel.EMAIL : p.getPreferredChannel());
        ClinicSettings cfg = settings.current();

        if (p.getContactConsent() != ContactConsent.GRANTED) {
            throw fail(automated, ApiException.conflict("NO_CONSENT", "The patient has not consented to notifications"));
        }
        boolean enabled = channel == NotificationChannel.EMAIL ? cfg.isEmailEnabled() : cfg.isSmsEnabled();
        if (!enabled) {
            throw fail(automated, ApiException.conflict("CHANNEL_DISABLED", "This notification channel is disabled"));
        }
        if (Recipients.forChannel(channel, p).isEmpty()) {
            throw fail(automated, ApiException.conflict("NO_RECIPIENT", "The patient has no valid address for this channel"));
        }
        boolean hasLink = followUpId != null ? links.hasCurrentCode(true, followUpId) : links.hasCurrentCode(false, appointmentId);
        if (!hasLink) {
            throw fail(automated, ApiException.conflict("LINK_UNAVAILABLE", "Generate a new link before sending it"));
        }
        Instant now = Instant.now(clock);
        Instant since = now.minus(Duration.ofHours(24));
        if (automated) {
            if (outbox.countRecentForPatient(p.getId(), since, COUNTED) >= props.maxAutomatedPerPatientPerDay()) {
                throw new Skip();
            }
        } else {
            long recent = followUpId != null
                    ? outbox.countByAutomatedFalseAndTemplateAndFollowUpIdAndCreatedAtAfter(template, followUpId, since)
                    : outbox.countByAutomatedFalseAndTemplateAndAppointmentIdAndCreatedAtAfter(template, appointmentId, since);
            if (recent >= props.maxManualPerTargetPerDay()) {
                throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "NOTIFICATION_LIMIT",
                        "Too many notifications for this patient; try again later");
            }
        }
        NotificationOutbox row = new NotificationOutbox();
        row.setPatientId(p.getId());
        row.setFollowUpId(followUpId);
        row.setAppointmentId(appointmentId);
        row.setChannel(channel);
        row.setTemplate(template);
        row.setLanguage(p.getPreferredLanguage() != null ? p.getPreferredLanguage() : cfg.getDefaultLanguage());
        row.setStatus(NotificationStatus.PENDING);
        row.setAutomated(automated);
        row.setDedupeKey(dedupeKey);
        row.setAttempts(0);
        row.setNextAttemptAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBySub(actor == null ? SYSTEM_SUB : actor.sub());
        row.setCreatedByName(actor == null ? null : actor.name());
        row = outbox.saveAndFlush(row);
        if (!automated) {
            audit.recordChange(actor, AuditAction.NOTIFICATION_REQUESTED, p.getId(), AuditResource.NOTIFICATION, row.getId(),
                    template + "/" + channel);
        }
        return row;
    }

    private static RuntimeException fail(boolean automated, ApiException manual) {
        return automated ? new Skip() : manual;
    }

    // ---- read

    @Transactional(readOnly = true)
    public PageResponse<NotificationDTO> listForPatient(Long patientId, int page, int size) {
        if (!patients.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        PageRequest pr = PageRequest.of(page, size);
        Slice<NotificationOutbox> slice = CurrentStaff.has(Permission.CLINICAL_READ)
                ? outbox.findByPatientIdOrderByCreatedAtDescIdDesc(patientId, pr)
                : outbox.findByPatientIdAndTemplateInOrderByCreatedAtDescIdDesc(patientId, APPOINTMENT_TEMPLATES, pr);
        return new PageResponse<>(slice.getContent().stream().map(NotificationService::toDto).toList(), page, size,
                slice.hasNext());
    }

    static NotificationDTO toDto(NotificationOutbox n) {
        return new NotificationDTO(n.getId(), n.getPatientId(), n.getFollowUpId(), n.getAppointmentId(), n.getTemplate(),
                n.getChannel(), n.getLanguage(), n.getStatus(), n.isAutomated(), n.getAttempts(), n.getLastError(),
                n.getRecipientMasked(), n.getCreatedAt(), n.getSentAt(), n.isAutomated() ? null : n.getCreatedByName());
    }

    /** Test/diagnostic helper. */
    Optional<NotificationOutbox> find(Long id) {
        return outbox.findById(id);
    }
}
