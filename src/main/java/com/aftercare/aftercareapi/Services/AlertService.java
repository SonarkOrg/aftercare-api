package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.AlertStatus;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.Alert;
import com.aftercare.aftercareapi.Models.CheckIn;
import com.aftercare.aftercareapi.Models.Dto.AlertDTO;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireRegistry;
import com.aftercare.aftercareapi.Questionnaires.RuleHit;
import com.aftercare.aftercareapi.Repositories.AlertRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class AlertService {

    /** status, then severity (HIGH first), then createdAt ascending, then id ascending. */
    static final Comparator<Alert> ORDER = Comparator
            .comparing(Alert::getStatus)
            .thenComparing(Alert::getSeverity)
            .thenComparing(Alert::getCreatedAt)
            .thenComparing(Alert::getId);

    private final AlertRepository alertRepository;
    private final PatientRepository patientRepository;
    private final QuestionnaireRegistry registry;
    private final ActivityService activity;
    private final ActorResolver actors;
    private final Clock clock;

    public AlertService(AlertRepository alertRepository, PatientRepository patientRepository,
                        QuestionnaireRegistry registry, ActivityService activity, ActorResolver actors, Clock clock) {
        this.alertRepository = alertRepository;
        this.patientRepository = patientRepository;
        this.registry = registry;
        this.activity = activity;
        this.actors = actors;
        this.clock = clock;
    }

    /** Saves one OPEN alert per ALERT hit; returns how many were created. */
    @Transactional
    public int createAlerts(CheckIn checkIn, List<RuleHit> hits) {
        // Stamp with the submission time so the timeline and time-to-acknowledge stay consistent (seeded check-ins are in the past).
        Instant now = checkIn.getSubmittedAt() != null ? checkIn.getSubmittedAt() : Instant.now(clock);
        int n = 0;
        for (RuleHit hit : hits) {
            AlertSeverity severity = hit.severity();
            if (severity == null) {
                continue;
            }
            Alert saved = alertRepository.save(Alert.builder()
                    .patient(checkIn.getFollowUp().getPatient())
                    .followUp(checkIn.getFollowUp())
                    .checkIn(checkIn)
                    .linkId(hit.linkId())
                    .answerValue(hit.answerSnapshot())
                    .severity(severity)
                    .messageEn(hit.messageEn())
                    .messageFr(hit.messageFr())
                    .status(AlertStatus.OPEN)
                    .createdAt(now)
                    .build());
            activity.record(checkIn.getFollowUp().getPatient().getId(), ActivityKind.ALERT_CREATED, now, ActorType.SYSTEM, null, null,
                    saved.getId(), severity, Texts.humanize(severity) + " alert: " + hit.messageEn());
            n++;
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<AlertDTO> list(Collection<AlertStatus> statuses) {
        Set<AlertStatus> wanted = statuses == null || statuses.isEmpty()
                ? EnumSet.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED) : EnumSet.copyOf(statuses);
        return alertRepository.findByStatusIn(wanted).stream().sorted(ORDER).map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<AlertDTO> listForPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        return alertRepository.findByPatientId(patientId).stream().sorted(ORDER).map(this::toDto).toList();
    }

    @Transactional
    public AlertDTO acknowledge(Long id, String actor) {
        Alert a = find(id);
        if (a.getStatus() != AlertStatus.OPEN) {
            throw ApiException.invalidState("Alert is not open");
        }
        a.setStatus(AlertStatus.ACKNOWLEDGED);
        a.setAcknowledgedAt(Instant.now(clock));
        a.setAcknowledgedBy(actor);
        alertRepository.saveAndFlush(a);
        recordStaff(a, ActivityKind.ALERT_ACKNOWLEDGED, "Alert acknowledged: ", a.getAcknowledgedAt(), actor);
        return toDto(a);
    }

    @Transactional
    public AlertDTO resolve(Long id, String note, String actor) {
        Alert a = find(id);
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty() || trimmed.length() > 1000) {
            throw ApiException.validation("note", "must not be blank and at most 1000 characters");
        }
        if (a.getStatus() == AlertStatus.RESOLVED) {
            throw ApiException.invalidState("Alert is already resolved");
        }
        a.setStatus(AlertStatus.RESOLVED);
        a.setResolvedAt(Instant.now(clock));
        a.setResolvedBy(actor);
        a.setResolutionNote(trimmed);
        alertRepository.saveAndFlush(a);
        recordStaff(a, ActivityKind.ALERT_RESOLVED, "Alert resolved: ", a.getResolvedAt(), actor);
        return toDto(a);
    }

    /** Seed helper: acknowledges with an explicit timestamp. */
    @Transactional
    public void acknowledgeForSeed(Alert a, Instant at, String actor) {
        a.setStatus(AlertStatus.ACKNOWLEDGED);
        a.setAcknowledgedAt(at);
        a.setAcknowledgedBy(actor);
        alertRepository.save(a);
        recordStaff(a, ActivityKind.ALERT_ACKNOWLEDGED, "Alert acknowledged: ", at, actor);
    }

    private void recordStaff(Alert a, ActivityKind kind, String prefix, Instant at, String actor) {
        activity.record(a.getPatient().getId(), kind, at, ActorType.STAFF, actor, actors.nameForSub(actor), a.getId(),
                a.getSeverity(), prefix + a.getMessageEn());
    }

    private Alert find(Long id) {
        return alertRepository.findById(id).orElseThrow(() -> ApiException.notFound("Alert not found"));
    }

    private AlertDTO toDto(Alert a) {
        CheckIn c = a.getCheckIn();
        var patient = a.getPatient();
        String label = registry.parsed(c.getQuestionnaire()).labelEn(a.getLinkId());
        return new AlertDTO(a.getId(), patient.getId(), patient.getFirstName(), patient.getLastName(),
                a.getFollowUp().getId(), c.getId(), c.getCheckInDate(), a.getLinkId(), label, a.getAnswerValue(),
                a.getSeverity(), a.getMessageEn(), a.getMessageFr(), a.getStatus(), a.getCreatedAt(),
                a.getAcknowledgedAt(), a.getAcknowledgedBy(), a.getResolvedAt(), a.getResolvedBy(), a.getResolutionNote());
    }
}
