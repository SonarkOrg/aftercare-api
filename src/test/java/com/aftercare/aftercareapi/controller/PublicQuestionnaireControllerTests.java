package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicQuestionnaireControllerTests extends IntegrationTest {

    private MvcResult pubGet(String code) throws Exception {
        return mockMvc.perform(get("/api/public/q/" + code)).andReturn();
    }

    private MvcResult pubPost(String code, String body) throws Exception {
        return mockMvc.perform(post("/api/public/q/" + code).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static Instant noon(String date) {
        return LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.of("America/Toronto")).toInstant();
    }

    @Test
    void errorDispatchOnPublicEndpointKeepsRealStatus() throws Exception {
        String code = newFollowUp(newPatient(), "VISCERAL_SURGERY", "2026-10-13", null).get("accessCode").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/public/q/" + code))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/public/q/" + code).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ---- check-in

    @Test
    void getWithoutAuthReturnsSessionWithoutRules() throws Exception {
        JsonNode r = newFollowUp(newPatient(), "VISCERAL_SURGERY", "2026-10-13", null);
        mockMvc.perform(get("/api/public/q/" + r.get("accessCode").asText()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        MvcResult res = pubGet(r.get("accessCode").asText());
        JsonNode s = json(res);
        assertEquals("FOLLOW_UP_CHECKIN", s.get("kind").asText());
        assertFalse(s.get("submitted").asBoolean());
        assertEquals("Questionnaire", s.get("questionnaire").get("resourceType").asText());
        assertEquals("2026-10-14", s.get("checkIn").get("checkInDate").asText());
        assertEquals("2026-10-20", s.get("checkIn").get("followUpEndDate").asText());
        assertTrue(s.get("appointment").isNull());
        String raw = res.getResponse().getContentAsString();
        assertFalse(raw.contains("\"rules\""));
        assertFalse(raw.contains("\"operator\""));
    }

    @Test
    void everyUnusableCodeGivesTheSame404() throws Exception {
        long pid = newPatient();
        JsonNode closed = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        call(post("/api/follow-ups/" + closed.get("followUp").get("id").asLong() + "/close"));
        JsonNode revoked = newFollowUp(newPatient(), "VISCERAL_SURGERY", "2026-10-13", null);
        call(post("/api/follow-ups/" + revoked.get("followUp").get("id").asLong() + "/access-code"));
        JsonNode expired = newFollowUp(newPatient(), "VISCERAL_SURGERY", "2026-10-13", 3); // end 10-16
        List<String> bodies = new ArrayList<>();
        for (String code : new String[]{"abc", "!".repeat(43), "A".repeat(43), closed.get("accessCode").asText(), revoked.get("accessCode").asText()}) {
            MvcResult r = pubGet(code);
            assertEquals(404, r.getResponse().getStatus(), code);
            bodies.add(r.getResponse().getContentAsString());
        }
        String expiredCode = expired.get("accessCode").asText();
        clock.set(noon("2026-10-17")); // endDate + 1: still valid
        assertEquals(200, pubGet(expiredCode).getResponse().getStatus());
        clock.set(noon("2026-10-18")); // endDate + 2
        MvcResult r = pubGet(expiredCode);
        assertEquals(404, r.getResponse().getStatus());
        bodies.add(r.getResponse().getContentAsString());
        assertEquals(404, pubPost(expiredCode, checkInBody(1, null, false)).getResponse().getStatus());
        for (String b : bodies) {
            assertEquals(bodies.get(0), b);
        }
        JsonNode e = om.readTree(bodies.get(0));
        assertEquals("NOT_FOUND", e.get("error").asText());
        assertEquals("Link is invalid or expired", e.get("message").asText());
    }

    @Test
    void validPostStoresNormalizedResponseAndAlerts() throws Exception {
        long pid = newPatient();
        JsonNode f = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        String body = checkInBody(8, 38.4, true).replace("\"status\":\"completed\"", "\"status\":\"completed\",\"id\":\"x\",\"subject\":{\"display\":\"Name\"}");
        MvcResult r = pubPost(f.get("accessCode").asText(), body);
        assertEquals(201, r.getResponse().getStatus());
        assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));
        JsonNode res = json(r);
        assertEquals("FOLLOW_UP_CHECKIN", res.get("kind").asText());
        assertEquals("2026-10-14", res.get("checkInDate").asText());
        assertTrue(res.has("submittedAt"));
        assertEquals(3, res.size());

        JsonNode stored = json(call(get("/api/patients/" + pid + "/check-ins"))).get(0).get("response");
        List<String> keys = new ArrayList<>();
        Iterator<String> it = stored.fieldNames();
        it.forEachRemaining(keys::add);
        assertEquals(List.of("resourceType", "questionnaire", "status", "authored", "item"), keys);
        assertEquals("valueDecimal", stored.get("item").get(1).get("answer").get(0).fieldNames().next());

        JsonNode alerts = json(call(get("/api/patients/" + pid + "/alerts")));
        assertEquals(3, alerts.size());
        for (JsonNode a : alerts) {
            assertFalse(a.get("message").asText().isBlank());
            assertFalse(a.get("messageFr").asText().isBlank());
            assertEquals("OPEN", a.get("status").asText());
        }
        assertEquals("Severe pain reported (8/10)", alerts.get(0).get("message").asText());
        assertEquals("Douleur sévère signalée (8/10)", alerts.get(0).get("messageFr").asText());
        assertEquals("38.4", alerts.get(1).get("answerValue").asText());
    }

    @Test
    void secondPostSameDayConflictsAndNextDayWorks() throws Exception {
        JsonNode f = newFollowUp(newPatient(), "VISCERAL_SURGERY", "2026-10-13", null);
        String code = f.get("accessCode").asText();
        assertEquals(201, pubPost(code, checkInBody(1, null, false)).getResponse().getStatus());
        MvcResult dup = pubPost(code, checkInBody(1, null, false));
        assertEquals(409, dup.getResponse().getStatus());
        assertEquals("ALREADY_SUBMITTED", json(dup).get("error").asText());
        assertTrue(json(pubGet(code)).get("submitted").asBoolean());
        clock.plus(java.time.Duration.ofDays(1));
        assertFalse(json(pubGet(code)).get("submitted").asBoolean());
        assertEquals(201, pubPost(code, checkInBody(1, null, false)).getResponse().getStatus());
    }

    @Test
    void checkInDateUsesTorontoTime() throws Exception {
        JsonNode f = newFollowUp(newPatient(), "VISCERAL_SURGERY", "2026-10-13", null);
        String code = f.get("accessCode").asText();
        clock.set(Instant.parse("2026-10-15T03:30:00Z"));
        MvcResult a = pubPost(code, checkInBody(1, null, false));
        assertEquals(201, a.getResponse().getStatus());
        assertEquals("2026-10-14", json(a).get("checkInDate").asText());
        clock.set(Instant.parse("2026-10-15T04:30:00Z"));
        MvcResult b = pubPost(code, checkInBody(1, null, false));
        assertEquals(201, b.getResponse().getStatus());
        assertEquals("2026-10-15", json(b).get("checkInDate").asText());
    }

    @Test
    void canonicalMismatchAndValidationPersistNothing() throws Exception {
        long pid = newPatient();
        JsonNode f = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        String code = f.get("accessCode").asText();
        MvcResult mismatch = pubPost(code, checkInBody(1, null, false).replace("|1", "|2"));
        assertEquals(400, mismatch.getResponse().getStatus());
        assertEquals("does not match the assigned questionnaire", json(mismatch).get("fieldErrors").get("questionnaire").asText());
        MvcResult bad = pubPost(code, checkInBody(99, null, false));
        assertEquals(400, bad.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(bad).get("error").asText());
        assertTrue(json(bad).get("fieldErrors").has("item.PAIN"));
        assertFalse(bad.getResponse().getContentAsString().contains("99"));
        assertEquals(0, json(call(get("/api/patients/" + pid + "/check-ins"))).size());
        assertEquals(0, json(call(get("/api/patients/" + pid + "/alerts"))).size());
        assertFalse(json(pubGet(code)).get("submitted").asBoolean());
        assertEquals(400, pubPost(code, "not json").getResponse().getStatus());
    }

    // ---- intake

    @Test
    void appointmentTypePicksQuestionnaire() throws Exception {
        long pid = newPatient();
        JsonNode surgery = newAppointment(pid, "SURGERY", "ORTHOPEDIC_SURGERY", "2026-10-16T08:00:00").get("appointment");
        assertTrue(surgery.get("questionnaireUrl").asText().endsWith("/pre-surgery"));
        assertEquals("2026-10-16T08:00:00", surgery.get("scheduledAt").asText());
        JsonNode consult = newAppointment(pid, "PRE_OP_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-16T09:00:00").get("appointment");
        assertTrue(consult.get("questionnaireUrl").asText().endsWith("/pre-consult"));
    }

    @Test
    void benignIntakeLeadsToReady() throws Exception {
        long pid = newPatient();
        JsonNode a = newAppointment(pid, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-16T08:00:00");
        String code = a.get("accessCode").asText();
        JsonNode s = json(pubGet(code));
        assertEquals("APPOINTMENT_INTAKE", s.get("kind").asText());
        assertEquals("2026-10-16T08:00:00", s.get("appointment").get("scheduledAt").asText());
        assertTrue(s.get("checkIn").isNull());
        assertFalse(s.has("patientFirstName"));
        MvcResult r = pubPost(code, intakeBody(CONSULT_URL, false, false, false));
        assertEquals(201, r.getResponse().getStatus());
        assertEquals("APPOINTMENT_INTAKE", json(r).get("kind").asText());
        assertTrue(json(r).get("checkInDate").isNull());
        long id = a.get("appointment").get("id").asLong();
        assertEquals("READY", json(call(get("/api/appointments/" + id))).get("status").asText());
        MvcResult dup = pubPost(code, intakeBody(CONSULT_URL, false, false, false));
        assertEquals(409, dup.getResponse().getStatus());
        assertEquals("ALREADY_SUBMITTED", json(dup).get("error").asText());
        assertTrue(json(pubGet(code)).get("submitted").asBoolean());
    }

    @Test
    void anticoagulantLeadsToNeedsReviewWithFlag() throws Exception {
        JsonNode a = newAppointment(newPatient(), "SURGERY", "ORTHOPEDIC_SURGERY", "2026-10-16T08:00:00");
        String code = a.get("accessCode").asText();
        MvcResult missing = pubPost(code, intakeBody(SURGERY_URL, true, true, false));
        assertEquals(400, missing.getResponse().getStatus());
        assertEquals("is required", json(missing).get("fieldErrors").get("item.ANTICOAGULANT_NAME").asText());
        assertEquals(201, pubPost(code, intakeBody(SURGERY_URL, true, true, true)).getResponse().getStatus());
        JsonNode d = json(call(get("/api/appointments/" + a.get("appointment").get("id").asLong())));
        assertEquals("NEEDS_REVIEW", d.get("status").asText());
        assertEquals(1, d.get("flagCount").asInt());
        assertEquals("TAKES_ANTICOAGULANT", d.get("intake").get("flags").get(0).get("linkId").asText());
    }

    @Test
    void unusableAppointmentLinksGive404() throws Exception {
        JsonNode a = newAppointment(newPatient(), "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-16T08:00:00");
        String code = a.get("accessCode").asText();
        clock.set(Instant.parse("2026-10-16T12:00:00Z")); // exactly scheduledAt
        assertEquals(404, pubGet(code).getResponse().getStatus());
        assertEquals(404, pubPost(code, intakeBody(CONSULT_URL, false, false, false)).getResponse().getStatus());
        clock.reset();
        for (String st : new String[]{"CANCELLED", "COMPLETED", "NO_SHOW"}) {
            JsonNode b = newAppointment(newPatient(), "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-16T08:00:00");
            assertEquals(200, call(post("/api/appointments/" + b.get("appointment").get("id").asLong() + "/status")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + st + "\"}")).getResponse().getStatus());
            assertEquals(404, pubGet(b.get("accessCode").asText()).getResponse().getStatus(), st);
        }
    }

    @Test
    void overlongTextGives400() throws Exception {
        JsonNode a = newAppointment(newPatient(), "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-16T08:00:00");
        String body = intakeBody(CONSULT_URL, false, false, false).replace("\"Douleur\"", "\"" + "x".repeat(1001) + "\"");
        MvcResult r = pubPost(a.get("accessCode").asText(), body);
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("must be at most 1000 characters", json(r).get("fieldErrors").get("item.REASON_FOR_VISIT").asText());
    }
}
