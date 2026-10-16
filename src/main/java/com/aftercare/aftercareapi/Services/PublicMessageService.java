package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Dto.MarkReadRequest;
import com.aftercare.aftercareapi.Models.Dto.MessageSendRequest;
import com.aftercare.aftercareapi.Models.Dto.PublicMessageDTO;
import com.aftercare.aftercareapi.Models.Dto.PublicThreadDTO;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.PatientAccessLink;
import com.aftercare.aftercareapi.Models.PatientMessage;
import com.aftercare.aftercareapi.Repositories.PatientMessageRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Patient side of messaging, authenticated only by the access code. Exposes no names, subs or link ids.
 * Sending is rate-limited per patient in the database: 10 per rolling hour and 30 per rolling 24 hours.
 */
@Service
public class PublicMessageService {

    private static final Logger log = LoggerFactory.getLogger(PublicMessageService.class);
    static final int HOURLY_LIMIT = 10;
    static final int DAILY_LIMIT = 30;

    private final AccessLinkService accessLinks;
    private final MessageService staffMessages;
    private final PatientMessageRepository messages;
    private final PatientRepository patients;
    private final ClinicSettingsService settings;
    private final ActivityService activity;
    private final AuditService audit;
    private final Clock clock;

    public PublicMessageService(AccessLinkService accessLinks, MessageService staffMessages, PatientMessageRepository messages,
                                PatientRepository patients, ClinicSettingsService settings, ActivityService activity,
                                AuditService audit, Clock clock) {
        this.accessLinks = accessLinks;
        this.staffMessages = staffMessages;
        this.messages = messages;
        this.patients = patients;
        this.settings = settings;
        this.activity = activity;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PublicThreadDTO thread(String code, Long before, int limit) {
        PatientAccessLink link = accessLinks.usableLink(code);
        MessageService.checkWindow(before, limit);
        Long patientId = patientId(link);
        MessageService.Window w = staffMessages.window(patientId, before, limit);
        return new PublicThreadDTO(settings.clinicName(), settings.clinicPhone(),
                w.items().stream().map(this::toDto).toList(), w.hasMore(), messages.countUnreadByPatient(patientId));
    }

    @Transactional
    public PublicMessageDTO send(String code, MessageSendRequest req) {
        PatientAccessLink link = accessLinks.usableLink(code);
        Long patientId = patientId(link);
        Fields f = new Fields();
        String body = f.body("body", req.body(), 1, MessageService.MAX_BODY);
        f.check();
        Patient patient = patients.findByIdForUpdate(patientId).orElseThrow(() -> ApiException.notFound(AccessLinkService.NOT_FOUND_MESSAGE));
        Instant now = Instant.now(clock);
        if (messages.countByPatientIdAndSenderAndCreatedAtAfter(patientId, MessageSender.PATIENT, now.minus(Duration.ofHours(1))) >= HOURLY_LIMIT
                || messages.countByPatientIdAndSenderAndCreatedAtAfter(patientId, MessageSender.PATIENT, now.minus(Duration.ofHours(24))) >= DAILY_LIMIT) {
            throw ApiException.rateLimited();
        }
        PatientMessage m = new PatientMessage();
        m.setPatient(patient);
        m.setSender(MessageSender.PATIENT);
        m.setBody(body);
        m.setCreatedAt(now);
        m.setAccessLinkId(link.getId());
        m = messages.saveAndFlush(m);
        activity.record(patientId, ActivityKind.MESSAGE_FROM_PATIENT, now, ActorType.PATIENT, null, null, m.getId(), null,
                Texts.preview(body), Texts.preview(body));
        audit.recordChange(new Actor("patient:link:" + link.getId(), "Patient"), AuditAction.MESSAGE_SENT, patientId,
                AuditResource.MESSAGE, m.getId(), "PATIENT");
        log.info("Patient message id={} patient={}", m.getId(), patientId);
        return toDto(m);
    }

    @Transactional
    public void markRead(String code, MarkReadRequest req) {
        PatientAccessLink link = accessLinks.usableLink(code);
        long upTo = MessageService.upToId(req);
        messages.markReadByPatient(patientId(link), upTo, Instant.now(clock));
    }

    private static Long patientId(PatientAccessLink link) {
        return link.getFollowUp() != null ? link.getFollowUp().getPatient().getId() : link.getAppointment().getPatient().getId();
    }

    private PublicMessageDTO toDto(PatientMessage m) {
        boolean fromPatient = m.getSender() == MessageSender.PATIENT;
        return new PublicMessageDTO(m.getId(), m.getSender(), fromPatient ? null : m.getStaffName(), m.getBody(), m.getCreatedAt(),
                fromPatient ? m.getReadByStaffAt() != null : m.getReadByPatientAt() != null);
    }
}
