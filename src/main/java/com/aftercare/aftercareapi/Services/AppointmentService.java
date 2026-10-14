package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AppointmentStatus;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.Appointment;
import com.aftercare.aftercareapi.Models.AppointmentIntake;
import com.aftercare.aftercareapi.Models.Dto.AppointmentDTO;
import com.aftercare.aftercareapi.Models.Dto.AppointmentDetailDTO;
import com.aftercare.aftercareapi.Models.Dto.AppointmentMetricsDTO;
import com.aftercare.aftercareapi.Models.Dto.AppointmentWithCodeDTO;
import com.aftercare.aftercareapi.Models.Dto.CreateAppointmentRequest;
import com.aftercare.aftercareapi.Models.Dto.IntakeDTO;
import com.aftercare.aftercareapi.Models.Dto.UpdateAppointmentRequest;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.QuestionnaireDefinition;
import com.aftercare.aftercareapi.Models.ReadinessFlag;
import com.aftercare.aftercareapi.Questionnaires.ParsedQuestionnaire;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireRegistry;
import com.aftercare.aftercareapi.Questionnaires.ResponseBuilder;
import com.aftercare.aftercareapi.Questionnaires.ResponseValidator;
import com.aftercare.aftercareapi.Questionnaires.RuleEvaluator;
import com.aftercare.aftercareapi.Questionnaires.RuleHit;
import com.aftercare.aftercareapi.Questionnaires.RuleOutcome;
import com.aftercare.aftercareapi.Questionnaires.ValidatedResponse;
import com.aftercare.aftercareapi.Repositories.AppointmentIntakeRepository;
import com.aftercare.aftercareapi.Repositories.AppointmentRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);
    private static final Set<AppointmentStatus> OPEN_STATUSES =
            EnumSet.of(AppointmentStatus.SCHEDULED, AppointmentStatus.READY, AppointmentStatus.NEEDS_REVIEW);
    private static final Duration MISSING_WINDOW = Duration.ofHours(48);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentIntakeRepository intakeRepository;
    private final PatientRepository patientRepository;
    private final QuestionnaireRegistry registry;
    private final AccessLinkService accessLinkService;
    private final ResponseValidator validator;
    private final RuleEvaluator evaluator;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AppointmentService(AppointmentRepository appointmentRepository, AppointmentIntakeRepository intakeRepository,
                              PatientRepository patientRepository, QuestionnaireRegistry registry,
                              AccessLinkService accessLinkService, ResponseValidator validator, RuleEvaluator evaluator,
                              ObjectMapper mapper, Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.intakeRepository = intakeRepository;
        this.patientRepository = patientRepository;
        this.registry = registry;
        this.accessLinkService = accessLinkService;
        this.validator = validator;
        this.evaluator = evaluator;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Link usable: SCHEDULED, READY or NEEDS_REVIEW, and now is before scheduledAt. */
    public static boolean linkUsable(Appointment a, Instant now) {
        return OPEN_STATUSES.contains(a.getStatus()) && now.isBefore(a.getScheduledAt());
    }

    // ---- staff operations

    @Transactional
    public AppointmentWithCodeDTO create(CreateAppointmentRequest req, String actor) {
        Patient patient = patientRepository.findById(req.patientId()).orElseThrow(PatientNotFoundException::new);
        Instant now = Instant.now(clock);
        Instant scheduled = toInstant(req.scheduledAt());
        if (!scheduled.isAfter(now)) {
            throw ApiException.validation("scheduledAt", "must be in the future");
        }
        QuestionnaireDefinition def = pick(req.type(), req.specialty());
        Appointment a = appointmentRepository.save(Appointment.builder()
                .patient(patient)
                .type(req.type())
                .specialty(req.specialty())
                .questionnaire(def)
                .scheduledAt(scheduled)
                .durationMinutes(req.durationMinutes())
                .location(blankToNull(req.location()))
                .clinician(blankToNull(req.clinician()))
                .status(AppointmentStatus.SCHEDULED)
                .createdAt(now)
                .createdBy(actor)
                .updatedAt(now)
                .build());
        String code = accessLinkService.issueForAppointment(a, actor);
        log.info("Created appointment {} for patient {}", a.getId(), patient.getId());
        return new AppointmentWithCodeDTO(toDto(a, null, accessLinkService.issuedAtForAppointment(a.getId()), now), code);
    }

    @Transactional(readOnly = true)
    public List<AppointmentDTO> list(LocalDate from, LocalDate to, Collection<AppointmentStatus> statuses) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from == null ? today : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f)) {
            throw ApiException.validation("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > 31) {
            throw ApiException.validation("to", "range must not exceed 31 days");
        }
        List<Appointment> found = inRange(f, t).stream()
                .filter(a -> statuses == null || statuses.isEmpty() || statuses.contains(a.getStatus()))
                .sorted(Comparator.comparing(Appointment::getScheduledAt).thenComparing(Appointment::getId))
                .toList();
        return toDtos(found);
    }

    @Transactional(readOnly = true)
    public AppointmentMetricsDTO metrics(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from == null ? today : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f)) {
            throw ApiException.validation("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > 366) {
            throw ApiException.validation("to", "range must not exceed 366 days");
        }
        List<Appointment> all = inRange(f, t);
        Instant now = Instant.now(clock);
        Map<Long, AppointmentIntake> intakes = intakesByAppointment(all);
        Map<AppointmentStatus, Long> counts = all.stream()
                .collect(Collectors.groupingBy(Appointment::getStatus, Collectors.counting()));
        int total = all.size();
        int cancelled = counts.getOrDefault(AppointmentStatus.CANCELLED, 0L).intValue();
        int completedQ = (int) all.stream()
                .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED && intakes.containsKey(a.getId())).count();
        int missing = (int) all.stream()
                .filter(a -> questionnaireMissing(a, intakes.containsKey(a.getId()), now)).count();
        int denominator = total - cancelled;
        Double rate = denominator == 0 ? null : (double) completedQ / denominator;
        return new AppointmentMetricsDTO(f, t, total,
                counts.getOrDefault(AppointmentStatus.SCHEDULED, 0L).intValue(),
                counts.getOrDefault(AppointmentStatus.READY, 0L).intValue(),
                counts.getOrDefault(AppointmentStatus.NEEDS_REVIEW, 0L).intValue(),
                cancelled,
                counts.getOrDefault(AppointmentStatus.COMPLETED, 0L).intValue(),
                counts.getOrDefault(AppointmentStatus.NO_SHOW, 0L).intValue(),
                completedQ, missing, rate);
    }

    @Transactional(readOnly = true)
    public AppointmentDetailDTO detail(Long id) {
        Appointment a = find(id);
        AppointmentIntake intake = intakeRepository.findByAppointmentId(id).orElse(null);
        Instant now = Instant.now(clock);
        AppointmentDTO dto = toDto(a, intake, accessLinkService.issuedAtForAppointment(id), now);
        return AppointmentDetailDTO.from(dto, intake == null ? null : toIntakeDto(intake));
    }

    @Transactional(readOnly = true)
    public List<AppointmentDTO> listForPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        List<Appointment> list = appointmentRepository.findByPatientId(patientId).stream()
                .sorted(Comparator.comparing(Appointment::getScheduledAt).thenComparing(Appointment::getId).reversed())
                .toList();
        return toDtos(list);
    }

    @Transactional
    public AppointmentDTO update(Long id, UpdateAppointmentRequest req) {
        Appointment a = find(id);
        if (!OPEN_STATUSES.contains(a.getStatus())) {
            throw ApiException.invalidState("Appointment can no longer be changed");
        }
        boolean hasIntake = intakeRepository.existsByAppointmentId(id);
        boolean typeChanged = req.type() != a.getType();
        boolean specialtyChanged = req.specialty() != a.getSpecialty();
        if (typeChanged && hasIntake) {
            throw ApiException.invalidState("Type cannot change after the questionnaire was submitted");
        }
        Instant now = Instant.now(clock);
        Instant scheduled = toInstant(req.scheduledAt());
        if (!scheduled.equals(a.getScheduledAt()) && !scheduled.isAfter(now)) {
            throw ApiException.validation("scheduledAt", "must be in the future");
        }
        if ((typeChanged || specialtyChanged) && !hasIntake) {
            a.setQuestionnaire(pick(req.type(), req.specialty()));
        }
        a.setType(req.type());
        a.setSpecialty(req.specialty());
        a.setScheduledAt(scheduled);
        a.setDurationMinutes(req.durationMinutes());
        a.setLocation(blankToNull(req.location()));
        a.setClinician(blankToNull(req.clinician()));
        a.setUpdatedAt(now);
        appointmentRepository.saveAndFlush(a);
        return single(a, now);
    }

    @Transactional
    public void delete(Long id) {
        Appointment a = find(id);
        if (a.getStatus() != AppointmentStatus.SCHEDULED || intakeRepository.existsByAppointmentId(id)) {
            throw ApiException.invalidState("Appointment cannot be deleted");
        }
        accessLinkService.deleteForAppointment(id);
        appointmentRepository.delete(a);
        appointmentRepository.flush();
        log.info("Deleted appointment {}", id);
    }

    @Transactional
    public AppointmentWithCodeDTO regenerateCode(Long id, String actor) {
        Appointment a = appointmentRepository.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Appointment not found"));
        Instant now = Instant.now(clock);
        if (a.getStatus() != AppointmentStatus.SCHEDULED || !now.isBefore(a.getScheduledAt())) {
            throw ApiException.invalidState("Access link cannot be regenerated");
        }
        String code = accessLinkService.issueForAppointment(a, actor);
        log.info("Regenerated access link for appointment {}", id);
        return new AppointmentWithCodeDTO(single(a, now), code);
    }

    @Transactional
    public AppointmentDTO review(Long id, String actor) {
        Appointment a = find(id);
        if (a.getStatus() != AppointmentStatus.NEEDS_REVIEW) {
            throw ApiException.invalidState("Appointment does not need review");
        }
        Instant now = Instant.now(clock);
        a.setStatus(AppointmentStatus.READY);
        a.setReviewedAt(now);
        a.setReviewedBy(actor);
        a.setUpdatedAt(now);
        appointmentRepository.saveAndFlush(a);
        return single(a, now);
    }

    @Transactional
    public AppointmentDTO changeStatus(Long id, AppointmentStatus target) {
        if (target != AppointmentStatus.CANCELLED && target != AppointmentStatus.COMPLETED
                && target != AppointmentStatus.NO_SHOW) {
            throw ApiException.validation("status", "must be CANCELLED, COMPLETED or NO_SHOW");
        }
        Appointment a = find(id);
        if (!OPEN_STATUSES.contains(a.getStatus())) {
            throw ApiException.invalidState("Appointment status can no longer be changed");
        }
        Instant now = Instant.now(clock);
        a.setStatus(target);
        a.setUpdatedAt(now);
        appointmentRepository.saveAndFlush(a);
        return single(a, now);
    }

    // ---- patient intake

    /** Patient submission for a usable appointment link (spec 4.3). */
    @Transactional
    public AppointmentIntake submitIntake(Appointment a, JsonNode body) {
        if (intakeRepository.existsByAppointmentId(a.getId())) {
            throw ApiException.conflict("ALREADY_SUBMITTED", "Already submitted");
        }
        ParsedQuestionnaire q = registry.parsed(a.getQuestionnaire());
        ValidatedResponse validated = validator.validate(q, body);
        return recordIntake(a, q, validated, Instant.now(clock), true);
    }

    /** Seed helper: runs the real validate/evaluate/store pipeline without the link-usability checks. */
    @Transactional
    public AppointmentIntake recordIntakeForSeed(Appointment a, Instant at, Map<String, Object> answers) {
        ParsedQuestionnaire q = registry.parsed(a.getQuestionnaire());
        JsonNode body = ResponseBuilder.build(q, answers, mapper);
        return recordIntake(a, q, validator.validate(q, body), at, false);
    }

    public boolean hasIntake(Long appointmentId) {
        return intakeRepository.existsByAppointmentId(appointmentId);
    }

    private AppointmentIntake recordIntake(Appointment a, ParsedQuestionnaire q, ValidatedResponse validated,
                                           Instant at, boolean enforceStatus) {
        List<RuleHit> hits = evaluator.evaluate(q.rules(), validated).stream()
                .filter(h -> h.outcomeType() == RuleOutcome.NEEDS_REVIEW).toList();
        String json;
        try {
            json = mapper.writeValueAsString(validated.toFhir(q, at, mapper));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize response", e);
        }
        List<ReadinessFlag> flags = new ArrayList<>();
        for (RuleHit h : hits) {
            flags.add(new ReadinessFlag(h.linkId(), h.messageEn(), h.messageFr()));
        }
        AppointmentIntake intake = AppointmentIntake.builder()
                .appointment(a)
                .submittedAt(at)
                .questionnaire(a.getQuestionnaire())
                .questionnaireUrl(q.url())
                .questionnaireVersion(q.version())
                .responseJson(json)
                .flags(flags)
                .build();
        try {
            intake = intakeRepository.saveAndFlush(intake);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("ALREADY_SUBMITTED", "Already submitted");
        }
        if (enforceStatus && a.getStatus() != AppointmentStatus.SCHEDULED) {
            throw ApiException.invalidState("Appointment can no longer accept a questionnaire");
        }
        a.setStatus(flags.isEmpty() ? AppointmentStatus.READY : AppointmentStatus.NEEDS_REVIEW);
        a.setUpdatedAt(at);
        appointmentRepository.saveAndFlush(a);
        log.info("Intake {} recorded for appointment {}: {} flag(s)", intake.getId(), a.getId(), flags.size());
        return intake;
    }

    // ---- helpers

    public static boolean questionnaireMissing(Appointment a, boolean hasIntake, Instant now) {
        return a.getStatus() == AppointmentStatus.SCHEDULED && !hasIntake
                && !now.isBefore(a.getScheduledAt().minus(MISSING_WINDOW));
    }

    private Instant toInstant(LocalDateTime ldt) {
        ZonedDateTime z = ZonedDateTime.of(ldt, clock.getZone());
        if (!z.toLocalDateTime().equals(ldt)) {
            throw ApiException.validation("scheduledAt", "does not exist in the America/Toronto time zone (daylight saving gap)");
        }
        return z.toInstant();
    }

    private LocalDateTime toLocal(Instant i) {
        return LocalDateTime.ofInstant(i, clock.getZone());
    }

    private QuestionnaireDefinition pick(com.aftercare.aftercareapi.Enums.AppointmentType type,
                                         com.aftercare.aftercareapi.Enums.Specialty specialty) {
        return registry.forAppointment(type, specialty)
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_QUESTIONNAIRE", "No active questionnaire for this appointment type"));
    }

    private List<Appointment> inRange(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(clock.getZone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        return appointmentRepository.findByScheduledAtGreaterThanEqualAndScheduledAtLessThan(start, end);
    }

    private Map<Long, AppointmentIntake> intakesByAppointment(List<Appointment> list) {
        if (list.isEmpty()) {
            return new HashMap<>();
        }
        return intakeRepository.findByAppointmentIdIn(list.stream().map(Appointment::getId).toList()).stream()
                .collect(Collectors.toMap(i -> i.getAppointment().getId(), Function.identity()));
    }

    private List<AppointmentDTO> toDtos(List<Appointment> list) {
        Instant now = Instant.now(clock);
        Map<Long, AppointmentIntake> intakes = intakesByAppointment(list);
        Map<Long, Instant> issued = accessLinkService.issuedAtForAppointments(list.stream().map(Appointment::getId).toList());
        return list.stream().map(a -> toDto(a, intakes.get(a.getId()), issued.get(a.getId()), now)).toList();
    }

    private AppointmentDTO single(Appointment a, Instant now) {
        return toDto(a, intakeRepository.findByAppointmentId(a.getId()).orElse(null),
                accessLinkService.issuedAtForAppointment(a.getId()), now);
    }

    private AppointmentDTO toDto(Appointment a, AppointmentIntake intake, Instant issuedAt, Instant now) {
        Patient p = a.getPatient();
        QuestionnaireDefinition q = a.getQuestionnaire();
        return new AppointmentDTO(a.getId(), p.getId(), p.getFirstName(), p.getLastName(), a.getType(), a.getSpecialty(),
                q.getId(), q.getUrl(), q.getVersion(), toLocal(a.getScheduledAt()), a.getDurationMinutes(),
                a.getLocation(), a.getClinician(), a.getStatus(),
                questionnaireMissing(a, intake != null, now),
                intake == null ? null : intake.getSubmittedAt(),
                intake == null ? 0 : intake.getFlags().size(),
                issuedAt, a.getReviewedAt(), a.getReviewedBy(), a.getCreatedAt(), a.getCreatedBy(), a.getUpdatedAt());
    }

    private IntakeDTO toIntakeDto(AppointmentIntake i) {
        ParsedQuestionnaire q = registry.parsed(i.getQuestionnaire());
        List<IntakeDTO.Flag> flags = i.getFlags().stream()
                .map(f -> new IntakeDTO.Flag(f.getLinkId(), q.labelEn(f.getLinkId()), f.getMessageEn(), f.getMessageFr()))
                .toList();
        try {
            return new IntakeDTO(i.getSubmittedAt(), i.getQuestionnaire().getId(), i.getQuestionnaireUrl(),
                    i.getQuestionnaireVersion(), mapper.readTree(i.getResponseJson()), flags);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response is not valid JSON", e);
        }
    }

    private Appointment find(Long id) {
        return appointmentRepository.findById(id).orElseThrow(() -> ApiException.notFound("Appointment not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
