package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.QuestionnaireDefinition;
import com.aftercare.aftercareapi.Repositories.QuestionnaireDefinitionRepository;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.aftercare.aftercareapi.support.Seeds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestionnaireLoaderTests extends IntegrationTest {

    @Autowired
    QuestionnaireLoader loader;
    @Autowired
    QuestionnaireDefinitionRepository repo;

    private QuestionnaireDefinition byName(String slug) {
        return repo.findByUrlAndVersion("https://aftercare.example/fhir/Questionnaire/" + slug, "1").orElseThrow();
    }

    @Test
    void seedLoadInsertsFourDefinitions() throws Exception {
        assertEquals(4, repo.count());
        QuestionnaireDefinition v = byName("followup-visceral-surgery");
        assertEquals(QuestionnairePurpose.FOLLOW_UP_CHECKIN, v.getPurpose());
        assertEquals(Specialty.VISCERAL_SURGERY, v.getSpecialty());
        assertTrue(v.getAppointmentTypes().isEmpty());
        assertEquals(Specialty.ORTHOPEDIC_SURGERY, byName("followup-orthopedic-surgery").getSpecialty());
        QuestionnaireDefinition s = byName("pre-surgery");
        assertEquals(QuestionnairePurpose.PRE_APPOINTMENT, s.getPurpose());
        assertNull(s.getSpecialty());
        assertEquals(Set.of(AppointmentType.SURGERY), s.getAppointmentTypes());
        assertEquals(Set.of(AppointmentType.PRE_OP_CONSULT, AppointmentType.POST_OP_FOLLOWUP, AppointmentType.GENERAL_CONSULT),
                byName("pre-consult").getAppointmentTypes());
        assertEquals(Seeds.wrapper("followup-visceral-surgery.v1.json").get("questionnaire"), om.readTree(v.getFhirJson()));
        assertEquals(Seeds.wrapper("followup-visceral-surgery.v1.json").get("rules"), om.readTree(v.getRulesJson()));
        assertEquals("Daily check-in — visceral surgery", v.getTitleEn());
    }

    @Test
    void secondLoadChangesNothing() {
        long loadedAt = byName("pre-consult").getLoadedAt().toEpochMilli();
        loader.load("classpath:questionnaires/*.json");
        assertEquals(4, repo.count());
        assertEquals(loadedAt, byName("pre-consult").getLoadedAt().toEpochMilli());
    }

    @Test
    void changedContentKeepsStoredCopyAndStatusSyncs(@TempDir Path dir) {
        String changed = Seeds.variant(dir, "pre-consult.v1.json", "pc.json", w ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) w.get("questionnaire")).put("title", "Changé"));
        String hashBefore = byName("pre-consult").getContentHash();
        loader.load(changed);
        assertEquals(hashBefore, byName("pre-consult").getContentHash());
        assertEquals("Questionnaire avant la consultation", byName("pre-consult").getTitleFr());

        String retired = Seeds.variant(dir, "pre-consult.v1.json", "pc2.json", w ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) w.get("questionnaire")).put("status", "retired"));
        loader.load(retired);
        assertEquals(QuestionnaireStatus.RETIRED, byName("pre-consult").getStatus());
        loader.load("classpath:questionnaires/pre-consult.v1.json");
        assertEquals(QuestionnaireStatus.ACTIVE, byName("pre-consult").getStatus());
    }

    @Test
    void invalidFixturesThrowNamingTheFile() {
        for (String f : List.of("bad-version", "missing-english", "unsupported-type", "rule-unknown-link",
                "wrapper-no-specialty", "rule-wrong-outcome", "not-json")) {
            InvalidQuestionnaireException e = assertThrows(InvalidQuestionnaireException.class,
                    () -> loader.load("classpath:questionnaires-invalid/" + f + ".json"), f);
            assertTrue(e.getMessage().startsWith(f + ".json: "), e.getMessage());
        }
        assertEquals(4, repo.count());
    }
}
