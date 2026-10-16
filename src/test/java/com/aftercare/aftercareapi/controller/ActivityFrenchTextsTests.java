package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.ActivityKind;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Every timeline kind written by the services carries a French twin; old rows read back as null. */
class ActivityFrenchTextsTests extends IntegrationTest {

    private void ok(int expected, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception {
        var r = call(b);
        assertEquals(expected, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        clock.plus(Duration.ofMinutes(1));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder jsonPost(String url, String body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void everyActivityKindHasAFrenchSummary() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        long fu = f.get("followUp").get("id").asLong();
        String code = f.get("accessCode").asText();
        clock.plus(Duration.ofMinutes(1));

        assertEquals(201, mockMvc.perform(post("/api/public/q/" + code).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(8, 38.4, true))).andReturn().getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));
        JsonNode alerts = json(call(get("/api/patients/" + p + "/alerts")));
        assertTrue(alerts.size() >= 2);
        ok(200, post("/api/alerts/" + alerts.get(0).get("id").asLong() + "/acknowledge"));
        ok(200, jsonPost("/api/alerts/" + alerts.get(1).get("id").asLong() + "/resolve", "{\"note\":\"Called\"}"));

        ok(201, jsonPost("/api/patients/" + p + "/notes", "{\"body\":\"A note\"}"));
        ok(201, multipart("/api/patients/" + p + "/documents", pdfFile("a.pdf"), "DISCHARGE_SUMMARY", "Summary"));
        long doc = jdbc.queryForObject("select id from patient_documents where patient_id = ?", Long.class, p);
        ok(204, jsonPost("/api/documents/" + doc + "/delete", "{\"reason\":\"uploaded twice\"}"));
        ok(201, jsonPost("/api/patients/" + p + "/messages", "{\"body\":\"Hello\"}"));
        assertEquals(201, mockMvc.perform(post("/api/public/q/" + code + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Bonjour\"}")).andReturn().getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));

        long appt = newAppointment(p, "SURGERY", "ORTHOPEDIC_SURGERY", "2026-10-20T09:00:00").get("appointment").get("id").asLong();
        clock.plus(Duration.ofMinutes(1));
        ok(200, put("/api/appointments/" + appt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SURGERY\",\"specialty\":\"ORTHOPEDIC_SURGERY\",\"scheduledAt\":\"2026-10-21T10:30:00\",\"durationMinutes\":45}"));
        JsonNode code2 = json(call(post("/api/appointments/" + appt + "/access-code")));
        assertEquals(201, mockMvc.perform(post("/api/public/q/" + code2.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(intakeBody(SURGERY_URL, true, true, true))).andReturn().getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));
        ok(200, jsonPost("/api/appointments/" + appt + "/status", "{\"status\":\"CANCELLED\"}"));
        long gone = newAppointment(p, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-22T08:00:00").get("appointment").get("id").asLong();
        clock.plus(Duration.ofMinutes(1));
        ok(204, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/appointments/" + gone));
        ok(200, post("/api/follow-ups/" + fu + "/close"));

        JsonNode items = json(call(get("/api/patients/" + p + "/timeline").param("size", "100"))).get("items");
        Map<ActivityKind, JsonNode> byKind = new HashMap<>();
        for (JsonNode i : items) {
            JsonNode fr = i.get("summaryFr");
            assertNotNull(fr, i.toString());
            assertFalse(fr.isNull(), i.get("kind") + " has no summaryFr");
            assertFalse(fr.asText().isBlank());
            byKind.put(ActivityKind.valueOf(i.get("kind").asText()), i);
        }
        // MISSED check-in alerts are covered by MissedCheckInJobTests; the rest are all here.
        Set<ActivityKind> seen = EnumSet.copyOf(byKind.keySet());
        assertEquals(EnumSet.allOf(ActivityKind.class), seen);

        assertEquals("Note d'évolution", byKind.get(ActivityKind.NOTE_ADDED).get("summaryFr").asText());
        assertEquals("Suivi quotidien reçu pour le 2026-10-14", byKind.get(ActivityKind.CHECK_IN_SUBMITTED).get("summaryFr").asText());
        assertEquals("Suivi postopératoire commencé : chirurgie viscérale (30 jours)",
                byKind.get(ActivityKind.FOLLOW_UP_STARTED).get("summaryFr").asText().replaceAll("\\(\\d+ jours\\)", "(30 jours)"));
        assertEquals("Suivi postopératoire terminé", byKind.get(ActivityKind.FOLLOW_UP_CLOSED).get("summaryFr").asText());
        assertEquals("Document téléversé : Summary (résumé de congé)", byKind.get(ActivityKind.DOCUMENT_UPLOADED).get("summaryFr").asText());
        assertEquals("Document supprimé : Summary", byKind.get(ActivityKind.DOCUMENT_DELETED).get("summaryFr").asText());
        assertEquals("Hello", byKind.get(ActivityKind.MESSAGE_TO_PATIENT).get("summaryFr").asText());
        assertEquals("Bonjour", byKind.get(ActivityKind.MESSAGE_FROM_PATIENT).get("summaryFr").asText());
        assertEquals("Chirurgie du 2026-10-21 : Prêt → Annulé".replace("Prêt", "À examiner"),
                byKind.get(ActivityKind.APPOINTMENT_STATUS_CHANGED).get("summaryFr").asText());
        assertEquals("Chirurgie déplacée au 2026-10-21 10:30", byKind.get(ActivityKind.APPOINTMENT_RESCHEDULED).get("summaryFr").asText());
        assertEquals("Consultation générale du 2026-10-22 supprimée", byKind.get(ActivityKind.APPOINTMENT_DELETED).get("summaryFr").asText());
        assertTrue(byKind.get(ActivityKind.APPOINTMENT_INTAKE_SUBMITTED).get("summaryFr").asText().startsWith("Questionnaire préalable reçu ("));
        assertTrue(byKind.get(ActivityKind.APPOINTMENT_CREATED).get("summaryFr").asText().startsWith("Chirurgie prévue le 2026-10-20 09:00"));
        assertTrue(byKind.get(ActivityKind.ALERT_CREATED).get("summaryFr").asText().startsWith("Alerte élevée : "));
        assertTrue(byKind.get(ActivityKind.ALERT_ACKNOWLEDGED).get("summaryFr").asText().startsWith("Alerte prise en charge : "));
        assertTrue(byKind.get(ActivityKind.ALERT_RESOLVED).get("summaryFr").asText().startsWith("Alerte résolue : "));
    }

    @Test
    void rowsWrittenBeforeV6ReadBackWithoutAFrenchSummary() throws Exception {
        long p = newPatient();
        jdbc.update("insert into patient_activity (patient_id, category, kind, occurred_at, actor_type, summary, summary_fr) "
                        + "values (?, 'CHECK_IN', 'CHECK_IN_SUBMITTED', ?, 'PATIENT', 'Check-in submitted for 2026-10-14', null)", p,
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-14T12:00:00Z")));
        JsonNode item = json(call(get("/api/patients/" + p + "/timeline"))).get("items").get(0);
        assertEquals("Check-in submitted for 2026-10-14", item.get("summary").asText());
        assertTrue(item.has("summaryFr"));
        assertTrue(item.get("summaryFr").isNull());
    }

    @Test
    void anOverlongFrenchSummaryIsCutWithAnEllipsis() {
        String cut = com.aftercare.aftercareapi.Services.ActivityService.cut("é".repeat(400));
        assertEquals(300, cut.length());
        assertTrue(cut.endsWith("…"));
        assertEquals("court", com.aftercare.aftercareapi.Services.ActivityService.cut("court"));
    }

    @Test
    void missedCheckInAlertsHaveNoFrenchQuestionLabel() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        long fu = f.get("followUp").get("id").asLong();
        jdbc.update("insert into alerts (patient_id, follow_up_id, alert_type, missed_date, severity, message_en, message_fr, status, created_at, version) "
                        + "values (?, ?, 'MISSED_CHECKIN', date '2026-10-14', 'MEDIUM', 'x', 'x', 'OPEN', ?, 0)", p, fu,
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-14T16:00:00Z")));
        JsonNode a = json(call(get("/api/patients/" + p + "/alerts"))).get(0);
        assertEquals("MISSED_CHECKIN", a.get("type").asText());
        assertTrue(a.has("questionLabelFr"));
        assertTrue(a.get("questionLabelFr").isNull());
        assertTrue(a.get("questionLabel").isNull());
    }
}
