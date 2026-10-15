package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ReportControllerTests extends IntegrationTest {

    @Autowired
    EntityManagerFactory emf;

    private void at(String utc) {
        clock.set(Instant.parse(utc));
    }

    private void checkIn(String code, int pain) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/public/q/" + code).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(pain, null, false))).andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    private void intake(String code, boolean anticoagulant) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/public/q/" + code).contentType(MediaType.APPLICATION_JSON)
                .content(intakeBody(CONSULT_URL, false, anticoagulant, false))).andReturn();
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    private void status(JsonNode appt, String status) throws Exception {
        assertEquals(200, call(post("/api/appointments/" + appt.get("appointment").get("id").asLong() + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}")).getResponse().getStatus());
    }

    private long alertId(long patientId) throws Exception {
        return json(call(get("/api/patients/" + patientId + "/alerts"))).get(0).get("id").asLong();
    }

    @Test
    void handBuiltScenario() throws Exception {
        at("2031-05-28T15:00:00Z");
        long a = newPatient();
        long b = newPatient();
        String codeA = newFollowUp(a, "VISCERAL_SURGERY", "2031-05-28", 14).get("accessCode").asText();
        JsonNode fuB = newFollowUp(b, "VISCERAL_SURGERY", "2031-05-28", 14);
        String codeB = fuB.get("accessCode").asText();

        JsonNode a1 = newAppointment(a, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2031-06-02T09:00:00");
        JsonNode a2 = newAppointment(a, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2031-06-03T09:00:00");
        JsonNode a3 = newAppointment(a, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2031-06-04T09:00:00");
        JsonNode a4 = newAppointment(a, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2031-06-05T09:00:00");
        intake(a1.get("accessCode").asText(), false);
        intake(a2.get("accessCode").asText(), false);
        intake(a4.get("accessCode").asText(), true);
        status(a1, "COMPLETED");
        status(a2, "CANCELLED");
        status(a3, "NO_SHOW");

        at("2031-05-29T15:00:00Z");
        checkIn(codeA, 2);
        checkIn(codeB, 2);

        at("2031-05-30T15:00:00Z");
        checkIn(codeA, 8);
        long alert1 = alertId(a);
        clock.plus(Duration.ofMinutes(30));
        assertEquals(200, call(post("/api/alerts/" + alert1 + "/acknowledge")).getResponse().getStatus());

        at("2031-05-31T15:00:00Z");
        assertEquals(200, call(post("/api/follow-ups/" + fuB.get("followUp").get("id").asLong() + "/close")).getResponse().getStatus());

        at("2031-06-01T15:00:00Z");
        checkIn(codeA, 6);
        long alert2 = json(call(get("/api/patients/" + a + "/alerts"))).findValues("id").stream()
                .mapToLong(JsonNode::asLong).filter(id -> id != alert1).findFirst().orElseThrow();
        clock.plus(Duration.ofMinutes(90));
        assertEquals(200, call(post("/api/alerts/" + alert2 + "/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"handled\"}")).getResponse().getStatus());

        at("2031-06-06T15:00:00Z");
        checkIn(codeA, 2); // today never counts

        JsonNode r = json(call(get("/api/reports/summary").param("from", "2031-05-28").param("to", "2031-06-10")));
        assertEquals("2031-05-28", r.get("from").asText());
        assertEquals("2031-06-10", r.get("to").asText());

        JsonNode ci = r.get("checkIns");
        assertEquals(10, ci.get("expected").asInt());
        assertEquals(4, ci.get("received").asInt());
        assertEquals(0.4, ci.get("completionRate").asDouble(), 1e-9);
        assertEquals("2031-06-05", ci.get("throughDate").asText());

        JsonNode fu = r.get("followUps");
        assertEquals(1, fu.get("activeNow").asInt());
        assertEquals(2, fu.get("started").asInt());
        assertEquals(1, fu.get("closed").asInt());

        JsonNode al = r.get("alerts");
        assertEquals(2, al.get("total").asInt());
        assertEquals(1, al.get("high").asInt());
        assertEquals(1, al.get("medium").asInt());
        assertEquals(2, al.get("responded").asInt());
        assertEquals(60.0, al.get("meanMinutesToAcknowledge").asDouble(), 1e-9); // (30 acknowledged + 90 resolved without ack) / 2
        assertEquals(1, al.get("resolved").asInt());
        assertEquals(90.0, al.get("meanMinutesToResolve").asDouble(), 1e-9);
        assertEquals(1, al.get("unresolved").asInt());

        JsonNode ap = r.get("appointments");
        assertEquals(4, ap.get("total").asInt());
        assertEquals(1, ap.get("completed").asInt());
        assertEquals(1, ap.get("cancelled").asInt());
        assertEquals(1, ap.get("noShow").asInt());
        assertEquals(3, ap.get("questionnairesExpected").asInt());
        assertEquals(2, ap.get("questionnairesCompleted").asInt());
        assertEquals(2.0 / 3, ap.get("questionnaireCompletionRate").asDouble(), 1e-9);
        assertEquals(1, ap.get("intakesWithFlags").asInt());
        assertEquals(0.5, ap.get("needsReviewRate").asDouble(), 1e-9);
        assertEquals(0.25, ap.get("cancellationRate").asDouble(), 1e-9);
        assertEquals(0.5, ap.get("noShowRate").asDouble(), 1e-9);

        JsonNode w = r.get("weeks");
        assertEquals(3, w.size());
        assertEquals("2031-05-26", w.get(0).get("weekStart").asText());
        assertEquals("2031-05-28", w.get(0).get("from").asText());
        assertEquals("2031-06-01", w.get(0).get("to").asText());
        assertEquals(6, w.get(0).get("checkInsExpected").asInt());
        assertEquals(4, w.get(0).get("checkInsReceived").asInt());
        assertEquals(1, w.get(0).get("alertsHigh").asInt());
        assertEquals(1, w.get(0).get("alertsMedium").asInt());
        assertEquals("2031-06-02", w.get(1).get("weekStart").asText());
        assertEquals("2031-06-08", w.get(1).get("to").asText());
        assertEquals(4, w.get(1).get("checkInsExpected").asInt());
        assertEquals(0, w.get(1).get("checkInsReceived").asInt());
        assertEquals("2031-06-09", w.get(2).get("from").asText());
        assertEquals("2031-06-10", w.get(2).get("to").asText());
        assertEquals(0, w.get(2).get("checkInsExpected").asInt());
    }

    @Test
    void emptyRangeHasNullRatesAndMeans() throws Exception {
        JsonNode r = json(call(get("/api/reports/summary").param("from", "2040-01-01").param("to", "2040-01-07")));
        assertTrue(r.get("checkIns").get("completionRate").isNull());
        assertEquals(0, r.get("checkIns").get("expected").asInt());
        assertTrue(r.get("alerts").get("meanMinutesToAcknowledge").isNull());
        assertTrue(r.get("alerts").get("meanMinutesToResolve").isNull());
        JsonNode ap = r.get("appointments");
        for (String f : new String[]{"questionnaireCompletionRate", "needsReviewRate", "cancellationRate", "noShowRate"}) {
            assertTrue(ap.get(f).isNull(), f);
        }
        assertEquals(2, r.get("weeks").size()); // Tue 2040-01-01 .. Mon 2040-01-07 touches two Monday-based weeks
    }

    @Test
    void defaultRangeIsLast28Days() throws Exception {
        JsonNode r = json(call(get("/api/reports/summary")));
        assertEquals("2026-10-14", r.get("to").asText());
        assertEquals("2026-09-17", r.get("from").asText());
        assertEquals("2026-10-13", r.get("checkIns").get("throughDate").asText());
        assertEquals(5, r.get("weeks").size());
        JsonNode onlyTo = json(call(get("/api/reports/summary").param("to", "2026-10-01")));
        assertEquals("2026-09-04", onlyTo.get("from").asText());
    }

    @Test
    void rangeValidation() throws Exception {
        MvcResult tooLong = call(get("/api/reports/summary").param("from", "2025-01-01").param("to", "2026-01-02"));
        assertEquals(400, tooLong.getResponse().getStatus());
        assertEquals("range must not exceed 366 days", json(tooLong).get("fieldErrors").get("to").asText());
        // exactly 366 days is fine
        assertEquals(200, call(get("/api/reports/summary").param("from", "2025-01-01").param("to", "2026-01-01")).getResponse().getStatus());
        MvcResult reversed = call(get("/api/reports/summary").param("from", "2026-10-10").param("to", "2026-10-01"));
        assertEquals(400, reversed.getResponse().getStatus());
        assertEquals("must not be before from", json(reversed).get("fieldErrors").get("to").asText());
        MvcResult bad = call(get("/api/reports/summary").param("from", "yesterday"));
        assertEquals(400, bad.getResponse().getStatus());
        assertEquals("invalid value", json(bad).get("fieldErrors").get("from").asText());
    }

    @Test
    void summaryRunsAtMostEightStatementsForTwentyPatients() throws Exception {
        for (int i = 0; i < 20; i++) {
            long p = newPatient();
            String code = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-10", null).get("accessCode").asText();
            if (i % 2 == 0) {
                at("2026-10-12T16:00:00Z");
                checkIn(code, 8);
                clock.reset();
            }
            newAppointment(p, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-20T08:00:00");
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        MvcResult r = call(get("/api/reports/summary").param("from", "2026-10-01").param("to", "2026-10-31"));
        assertEquals(200, r.getResponse().getStatus());
        long statements = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);
        assertTrue(statements <= 8, "statements=" + statements);
        assertTrue(json(r).get("alerts").get("total").asInt() >= 10);
    }
}
