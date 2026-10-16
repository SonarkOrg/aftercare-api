package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Enums.FollowUpDisplayStatus;
import com.aftercare.aftercareapi.Enums.FollowUpStatus;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Models.Dto.CreateFollowUpRequest;
import com.aftercare.aftercareapi.Models.Dto.FollowUpDTO;
import com.aftercare.aftercareapi.Models.Dto.FollowUpWithCodeDTO;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.QuestionnaireDefinition;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireRegistry;
import com.aftercare.aftercareapi.Services.notifications.NotificationTriggers;
import com.aftercare.aftercareapi.Repositories.FollowUpRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);

    private final PatientRepository patientRepository;
    private final FollowUpRepository followUpRepository;
    private final QuestionnaireRegistry registry;
    private final AccessLinkService accessLinkService;
    private final ActivityService activity;
    private final ActorResolver actors;
    private final NotificationTriggers triggers;
    private final Clock clock;

    public FollowUpService(PatientRepository patientRepository, FollowUpRepository followUpRepository,
                           QuestionnaireRegistry registry, AccessLinkService accessLinkService,
                           ActivityService activity, ActorResolver actors, NotificationTriggers triggers, Clock clock) {
        this.triggers = triggers;
        this.patientRepository = patientRepository;
        this.followUpRepository = followUpRepository;
        this.registry = registry;
        this.accessLinkService = accessLinkService;
        this.activity = activity;
        this.actors = actors;
        this.clock = clock;
    }

    public static FollowUpDisplayStatus displayStatus(FollowUp f, LocalDate today) {
        if (f.getStatus() == FollowUpStatus.CLOSED) {
            return FollowUpDisplayStatus.CLOSED;
        }
        return today.isAfter(f.getEndDate().plusDays(1)) ? FollowUpDisplayStatus.EXPIRED : FollowUpDisplayStatus.ACTIVE;
    }

    /** One grace day after the end date. */
    public static boolean linkUsable(FollowUp f, LocalDate today) {
        return f.getStatus() == FollowUpStatus.ACTIVE && !today.isAfter(f.getEndDate().plusDays(1));
    }

    @Transactional
    public FollowUpWithCodeDTO create(Long patientId, CreateFollowUpRequest req, String actor) {
        Patient patient = patientRepository.findByIdForUpdate(patientId).orElseThrow(PatientNotFoundException::new);
        LocalDate today = LocalDate.now(clock);
        Map<String, String> errors = new LinkedHashMap<>();
        if (req.dischargeDate().isAfter(today)) {
            errors.put("dischargeDate", "must not be in the future");
        } else if (req.dischargeDate().isBefore(today.minusDays(30))) {
            errors.put("dischargeDate", "must be within the last 30 days");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        int duration = req.durationDays() == null ? 7 : req.durationDays();

        QuestionnaireDefinition def = registry.forFollowUp(req.specialty())
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_QUESTIONNAIRE", "No active questionnaire for this specialty"));

        Instant now = Instant.now(clock);
        for (FollowUp existing : followUpRepository.findByPatientIdAndStatus(patientId, FollowUpStatus.ACTIVE)) {
            if (displayStatus(existing, today) == FollowUpDisplayStatus.ACTIVE) {
                throw ApiException.conflict("FOLLOW_UP_ALREADY_ACTIVE", "Patient already has an active follow-up");
            }
            existing.setStatus(FollowUpStatus.CLOSED);
            existing.setClosedAt(now);
            existing.setClosedBy(actor);
            followUpRepository.save(existing);
        }
        followUpRepository.flush();

        FollowUp saved = followUpRepository.save(FollowUp.builder()
                .patient(patient)
                .specialty(req.specialty())
                .questionnaire(def)
                .dischargeDate(req.dischargeDate())
                .durationDays(duration)
                .endDate(req.dischargeDate().plusDays(duration))
                .status(FollowUpStatus.ACTIVE)
                .createdAt(now)
                .createdBy(actor)
                .build());
        String code = accessLinkService.issueForFollowUp(saved, actor);
        activity.record(patientId, ActivityKind.FOLLOW_UP_STARTED, now, ActorType.STAFF, actor, actors.nameForSub(actor),
                saved.getId(), null, Texts.humanize(req.specialty()) + " follow-up started (" + duration + " days)");
        triggers.followUpEnrolled(patientId, saved.getId());
        log.info("Created follow-up {} for patient {}", saved.getId(), patientId);
        return new FollowUpWithCodeDTO(toDto(saved, today), code);
    }

    @Transactional(readOnly = true)
    public List<FollowUpDTO> listForPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        LocalDate today = LocalDate.now(clock);
        List<FollowUp> list = followUpRepository.findByPatientIdOrderByCreatedAtDescIdDesc(patientId);
        Map<Long, Instant> issued = accessLinkService.issuedAtForFollowUps(list.stream().map(FollowUp::getId).toList());
        return list.stream()
                .map(f -> FollowUpDTO.from(f, displayStatus(f, today), issued.get(f.getId())))
                .toList();
    }

    @Transactional
    public FollowUpWithCodeDTO regenerateCode(Long id, String actor) {
        FollowUp f = followUpRepository.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Follow-up not found"));
        LocalDate today = LocalDate.now(clock);
        if (displayStatus(f, today) != FollowUpDisplayStatus.ACTIVE) {
            throw ApiException.invalidState("Follow-up is not active");
        }
        String code = accessLinkService.issueForFollowUp(f, actor);
        log.info("Regenerated access link for follow-up {}", id);
        return new FollowUpWithCodeDTO(toDto(f, today), code);
    }

    @Transactional
    public FollowUpDTO close(Long id, String actor) {
        FollowUp f = find(id);
        if (f.getStatus() != FollowUpStatus.ACTIVE) {
            throw ApiException.invalidState("Follow-up is already closed");
        }
        f.setStatus(FollowUpStatus.CLOSED);
        f.setClosedAt(Instant.now(clock));
        f.setClosedBy(actor);
        followUpRepository.saveAndFlush(f);
        activity.record(f.getPatient().getId(), ActivityKind.FOLLOW_UP_CLOSED, f.getClosedAt(), ActorType.STAFF, actor,
                actors.nameForSub(actor), f.getId(), null, "Follow-up closed");
        log.info("Closed follow-up {}", id);
        return toDto(f, LocalDate.now(clock));
    }

    /** The ACTIVE-status follow-up of each patient (its display status may be EXPIRED), keyed by patient id. */
    @Transactional(readOnly = true)
    public Map<Long, FollowUpDTO> activeByPatient() {
        LocalDate today = LocalDate.now(clock);
        List<FollowUp> active = followUpRepository.findByStatus(FollowUpStatus.ACTIVE);
        Map<Long, Instant> issued = accessLinkService.issuedAtForFollowUps(active.stream().map(FollowUp::getId).toList());
        Map<Long, FollowUpDTO> out = new HashMap<>();
        for (FollowUp f : active) {
            out.put(f.getPatient().getId(), FollowUpDTO.from(f, displayStatus(f, today), issued.get(f.getId())));
        }
        return out;
    }

    FollowUpDTO toDto(FollowUp f, LocalDate today) {
        return FollowUpDTO.from(f, displayStatus(f, today), accessLinkService.issuedAtForFollowUp(f.getId()));
    }

    private FollowUp find(Long id) {
        return followUpRepository.findById(id).orElseThrow(() -> ApiException.notFound("Follow-up not found"));
    }
}
