package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.CheckIn;
import com.aftercare.aftercareapi.Models.Dto.CheckInDTO;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Questionnaires.ParsedQuestionnaire;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireRegistry;
import com.aftercare.aftercareapi.Questionnaires.ResponseBuilder;
import com.aftercare.aftercareapi.Questionnaires.ResponseValidator;
import com.aftercare.aftercareapi.Questionnaires.RuleEvaluator;
import com.aftercare.aftercareapi.Questionnaires.RuleHit;
import com.aftercare.aftercareapi.Questionnaires.RuleOutcome;
import com.aftercare.aftercareapi.Questionnaires.ValidatedResponse;
import com.aftercare.aftercareapi.Repositories.CheckInRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class CheckInService {

    private static final Logger log = LoggerFactory.getLogger(CheckInService.class);

    private final CheckInRepository checkInRepository;
    private final PatientRepository patientRepository;
    private final QuestionnaireRegistry registry;
    private final ResponseValidator validator;
    private final RuleEvaluator evaluator;
    private final AlertService alertService;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CheckInService(CheckInRepository checkInRepository, PatientRepository patientRepository,
                          QuestionnaireRegistry registry, ResponseValidator validator, RuleEvaluator evaluator,
                          AlertService alertService, ObjectMapper mapper, Clock clock) {
        this.checkInRepository = checkInRepository;
        this.patientRepository = patientRepository;
        this.registry = registry;
        this.validator = validator;
        this.evaluator = evaluator;
        this.alertService = alertService;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Patient submission for a usable follow-up link (spec 4.2). */
    @Transactional
    public CheckIn submit(FollowUp followUp, JsonNode body) {
        LocalDate today = LocalDate.now(clock);
        if (checkInRepository.existsByFollowUpIdAndCheckInDate(followUp.getId(), today)) {
            throw ApiException.conflict("ALREADY_SUBMITTED", "Already submitted");
        }
        ParsedQuestionnaire q = registry.parsed(followUp.getQuestionnaire());
        ValidatedResponse validated = validator.validate(q, body);
        return record(followUp, q, validated, today, Instant.now(clock));
    }

    /** Seed helper: builds a response from a plain map and runs it through the real validate/evaluate/store pipeline. */
    @Transactional
    public CheckIn recordForSeed(FollowUp followUp, LocalDate date, Instant at, Map<String, Object> answers) {
        ParsedQuestionnaire q = registry.parsed(followUp.getQuestionnaire());
        JsonNode body = ResponseBuilder.build(q, answers, mapper);
        return record(followUp, q, validator.validate(q, body), date, at);
    }

    private CheckIn record(FollowUp followUp, ParsedQuestionnaire q, ValidatedResponse validated, LocalDate date, Instant at) {
        String json;
        try {
            json = mapper.writeValueAsString(validated.toFhir(q, at, mapper));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize response", e);
        }
        CheckIn checkIn;
        try {
            checkIn = checkInRepository.saveAndFlush(CheckIn.builder()
                    .followUp(followUp)
                    .checkInDate(date)
                    .submittedAt(at)
                    .questionnaire(followUp.getQuestionnaire())
                    .questionnaireUrl(q.url())
                    .questionnaireVersion(q.version())
                    .responseJson(json)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("ALREADY_SUBMITTED", "Already submitted");
        }
        List<RuleHit> hits = evaluator.evaluate(q.rules(), validated).stream()
                .filter(h -> h.outcomeType() == RuleOutcome.ALERT).toList();
        int alerts = alertService.createAlerts(checkIn, hits);
        log.info("Check-in {} recorded for follow-up {}: {} alert(s)", checkIn.getId(), followUp.getId(), alerts);
        return checkIn;
    }

    @Transactional(readOnly = true)
    public boolean existsToday(FollowUp followUp) {
        return checkInRepository.existsByFollowUpIdAndCheckInDate(followUp.getId(), LocalDate.now(clock));
    }

    @Transactional(readOnly = true)
    public List<CheckInDTO> listForPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        return checkInRepository.findByFollowUpPatientIdOrderByCheckInDateDescIdDesc(patientId).stream()
                .map(c -> CheckInDTO.from(c, parse(c.getResponseJson())))
                .toList();
    }

    /** Staff read of check-ins for all patients with checkInDate in [from, to]. Same range rules as appointments. */
    @Transactional(readOnly = true)
    public List<CheckInDTO> listRange(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from == null ? today : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f)) {
            throw ApiException.validation("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > 31) {
            throw ApiException.validation("to", "range must not exceed 31 days");
        }
        return checkInRepository.findByCheckInDateBetweenOrderByCheckInDateAscIdAsc(f, t).stream()
                .map(c -> CheckInDTO.from(c, parse(c.getResponseJson())))
                .toList();
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response is not valid JSON", e);
        }
    }
}
