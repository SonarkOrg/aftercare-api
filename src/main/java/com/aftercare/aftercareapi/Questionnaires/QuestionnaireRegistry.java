package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.AppointmentType;
import com.aftercare.aftercareapi.Enums.QuestionnairePurpose;
import com.aftercare.aftercareapi.Enums.QuestionnaireStatus;
import com.aftercare.aftercareapi.Enums.Specialty;
import com.aftercare.aftercareapi.Models.QuestionnaireDefinition;
import com.aftercare.aftercareapi.Repositories.QuestionnaireDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Selects the questionnaire definition to pin and caches parsed definitions (immutable per url+version). */
@Component
public class QuestionnaireRegistry {

    private final QuestionnaireDefinitionRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, ParsedQuestionnaire> cache = new ConcurrentHashMap<>();

    public QuestionnaireRegistry(QuestionnaireDefinitionRepository repository) {
        this.repository = repository;
    }

    public ParsedQuestionnaire parsed(QuestionnaireDefinition d) {
        ParsedQuestionnaire cached = cache.get(d.getId());
        if (cached != null) {
            return cached;
        }
        try {
            ParsedQuestionnaire q = QuestionnaireParser.parse(mapper.readTree(d.getFhirJson()));
            JsonNode rules = mapper.readTree(d.getRulesJson());
            ParsedQuestionnaire full = q.withRules(RuleParser.parse(rules, q, d.getPurpose()));
            cache.put(d.getId(), full);
            return full;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored questionnaire " + d.getId() + " is not valid JSON", e);
        }
    }

    public Optional<QuestionnaireDefinition> forFollowUp(Specialty specialty) {
        List<QuestionnaireDefinition> candidates = repository.findAll().stream()
                .filter(d -> d.getStatus() == QuestionnaireStatus.ACTIVE
                        && d.getPurpose() == QuestionnairePurpose.FOLLOW_UP_CHECKIN
                        && d.getSpecialty() == specialty)
                .toList();
        // Highest version per url, then the lowest id among the urls.
        return candidates.stream()
                .collect(java.util.stream.Collectors.groupingBy(QuestionnaireDefinition::getUrl))
                .values().stream()
                .map(group -> group.stream().max(byVersion().thenComparing(Comparator.comparing(QuestionnaireDefinition::getId).reversed())).orElseThrow())
                .min(Comparator.comparing(QuestionnaireDefinition::getId));
    }

    public Optional<QuestionnaireDefinition> forAppointment(AppointmentType type, Specialty specialty) {
        return repository.findAll().stream()
                .filter(d -> d.getStatus() == QuestionnaireStatus.ACTIVE
                        && d.getPurpose() == QuestionnairePurpose.PRE_APPOINTMENT
                        && d.getAppointmentTypes().contains(type)
                        && (d.getSpecialty() == null || d.getSpecialty() == specialty))
                .min(Comparator.<QuestionnaireDefinition, Integer>comparing(d -> d.getSpecialty() == specialty ? 0 : 1)
                        .thenComparing(byVersion().reversed())
                        .thenComparing(QuestionnaireDefinition::getId));
    }

    private static Comparator<QuestionnaireDefinition> byVersion() {
        return Comparator.comparing(d -> new BigInteger(d.getVersion()));
    }
}
