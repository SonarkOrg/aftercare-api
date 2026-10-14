package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.QuestionnaireDefinition;
import com.aftercare.aftercareapi.Repositories.QuestionnaireDefinitionRepository;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.aftercare.aftercareapi.support.Seeds;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuestionnaireRegistryTests extends IntegrationTest {

    @Autowired
    QuestionnaireLoader loader;
    @Autowired
    QuestionnaireRegistry registry;
    @Autowired
    QuestionnaireDefinitionRepository repo;

    @Test
    void mappingCoversAllAppointmentTypes() {
        assertTrue(registry.forAppointment(AppointmentType.SURGERY, Specialty.ORTHOPEDIC_SURGERY).orElseThrow().getUrl().endsWith("/pre-surgery"));
        for (AppointmentType t : List.of(AppointmentType.PRE_OP_CONSULT, AppointmentType.POST_OP_FOLLOWUP, AppointmentType.GENERAL_CONSULT)) {
            assertTrue(registry.forAppointment(t, Specialty.VISCERAL_SURGERY).orElseThrow().getUrl().endsWith("/pre-consult"));
        }
    }

    @Test
    void highestActiveVersionWinsAndRetiredIgnored(@TempDir Path dir) {
        loader.load(Seeds.variant(dir, "followup-visceral-surgery.v1.json", "v2.json", w ->
                ((ObjectNode) w.get("questionnaire")).put("version", "2")));
        loader.load(Seeds.variant(dir, "followup-visceral-surgery.v1.json", "v10.json", w ->
                ((ObjectNode) w.get("questionnaire")).put("version", "10")));
        assertEquals("10", registry.forFollowUp(Specialty.VISCERAL_SURGERY).orElseThrow().getVersion());
        loader.load(Seeds.variant(dir, "followup-visceral-surgery.v1.json", "v11.json", w ->
                ((ObjectNode) w.get("questionnaire")).put("version", "11").put("status", "retired")));
        assertEquals("10", registry.forFollowUp(Specialty.VISCERAL_SURGERY).orElseThrow().getVersion());
    }

    @Test
    void specialtySpecificPreAppointmentWins(@TempDir Path dir) {
        loader.load(Seeds.variant(dir, "pre-consult.v1.json", "ortho.json", w -> {
            w.put("specialty", "ORTHOPEDIC_SURGERY");
            w.putArray("appointmentTypes").add("GENERAL_CONSULT");
            ObjectNode q = (ObjectNode) w.get("questionnaire");
            q.put("url", "https://aftercare.example/fhir/Questionnaire/pre-consult-ortho");
        }));
        assertTrue(registry.forAppointment(AppointmentType.GENERAL_CONSULT, Specialty.ORTHOPEDIC_SURGERY).orElseThrow().getUrl().endsWith("pre-consult-ortho"));
        assertTrue(registry.forAppointment(AppointmentType.GENERAL_CONSULT, Specialty.VISCERAL_SURGERY).orElseThrow().getUrl().endsWith("/pre-consult"));
    }

    @Test
    void emptyWhenNothingMatchesGives409ThroughApi() throws Exception {
        long patient = newPatient();
        List<QuestionnaireDefinition> all = repo.findAll();
        try {
            all.forEach(d -> {
                d.setStatus(QuestionnaireStatus.RETIRED);
                repo.save(d);
            });
            assertTrue(registry.forFollowUp(Specialty.VISCERAL_SURGERY).isEmpty());
            assertTrue(registry.forAppointment(AppointmentType.SURGERY, Specialty.VISCERAL_SURGERY).isEmpty());
            var r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/patients/" + patient + "/follow-ups")
                    .contentType("application/json").content("{\"specialty\":\"VISCERAL_SURGERY\",\"dischargeDate\":\"2026-10-13\"}"));
            assertEquals(409, r.getResponse().getStatus());
            assertEquals("NO_ACTIVE_QUESTIONNAIRE", json(r).get("error").asText());
            var r2 = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/appointments")
                    .contentType("application/json").content("{\"patientId\":" + patient + ",\"type\":\"SURGERY\",\"specialty\":\"VISCERAL_SURGERY\",\"scheduledAt\":\"2026-10-20T08:00:00\",\"durationMinutes\":30}"));
            assertEquals(409, r2.getResponse().getStatus());
            assertEquals("NO_ACTIVE_QUESTIONNAIRE", json(r2).get("error").asText());
        } finally {
            all.forEach(d -> d.setStatus(QuestionnaireStatus.ACTIVE));
            repo.saveAll(all);
        }
    }
}
