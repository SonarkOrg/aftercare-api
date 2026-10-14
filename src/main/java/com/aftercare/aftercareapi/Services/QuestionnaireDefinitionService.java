package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Dto.QuestionnaireDefinitionDTO;
import com.aftercare.aftercareapi.Models.Dto.QuestionnaireDefinitionDetailDTO;
import com.aftercare.aftercareapi.Models.QuestionnaireDefinition;
import com.aftercare.aftercareapi.Repositories.QuestionnaireDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;

@Service
public class QuestionnaireDefinitionService {

    private final QuestionnaireDefinitionRepository repository;
    private final ObjectMapper mapper;

    public QuestionnaireDefinitionService(QuestionnaireDefinitionRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Ordered by url ascending, then version descending (numeric). */
    @Transactional(readOnly = true)
    public List<QuestionnaireDefinitionDTO> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(QuestionnaireDefinition::getUrl)
                        .thenComparing(Comparator.comparing((QuestionnaireDefinition d) -> new BigInteger(d.getVersion())).reversed()))
                .map(QuestionnaireDefinitionDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public QuestionnaireDefinitionDetailDTO detail(Long id) {
        QuestionnaireDefinition d = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Questionnaire not found"));
        try {
            return QuestionnaireDefinitionDetailDTO.from(QuestionnaireDefinitionDTO.from(d),
                    mapper.readTree(d.getFhirJson()), mapper.readTree(d.getRulesJson()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored questionnaire is not valid JSON", e);
        }
    }
}
