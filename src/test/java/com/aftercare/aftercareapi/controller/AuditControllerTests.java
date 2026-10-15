package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AuditControllerTests extends IntegrationTest {

    private static final String RAMQ = "TREM68541212";
    private static final String NOTE_BODY = "Confidential note text 9981";
    private static final String FILENAME = "secret-filename-xyz.pdf";

    @Test
    void scenarioIsAuditedWithoutPhi() throws Exception {
        long p = newPatient();
        var who = staffAs("auth0|audit-actor", "Audit Actor");
        as(who, put("/api/patients/" + p + "/health-number").contentType(MediaType.APPLICATION_JSON).content("{\"healthNumber\":\"" + RAMQ + "\"}"));
        clock.plus(Duration.ofMinutes(1));
        as(who, post("/api/patients/" + p + "/allergies").contentType(MediaType.APPLICATION_JSON).content("{\"substance\":\"Peanut\",\"severity\":\"SEVERE\"}"));
        clock.plus(Duration.ofMinutes(1));
        as(who, post("/api/patients/" + p + "/notes").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + NOTE_BODY + "\"}"));
        clock.plus(Duration.ofMinutes(1));
        as(who, multipart("/api/patients/" + p + "/documents", pdfFile(FILENAME), "OTHER", "Secret title"));
        clock.plus(Duration.ofMinutes(1));
        as(who, post("/api/patients/" + p + "/health-number/reveal").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Insurance claim\"}"));

        JsonNode page = json(as(who, get("/api/patients/" + p + "/audit")));
        JsonNode items = page.get("items");
        assertEquals("HEALTH_NUMBER_REVEALED", items.get(0).get("action").asText());
        assertEquals("Audit Actor", items.get(0).get("actorName").asText());
        assertEquals("auth0|audit-actor", items.get(0).get("actorSub").asText());
        assertEquals("reason: Insurance claim", items.get(0).get("detail").asText());
        long prevId = Long.MAX_VALUE;
        String prevAt = "9";
        for (JsonNode i : items) {
            assertTrue(i.get("id").asLong() < prevId);
            assertTrue(i.get("occurredAt").asText().compareTo(prevAt) <= 0);
            prevId = i.get("id").asLong();
            prevAt = i.get("occurredAt").asText();
        }
        List<String> actions = items.findValuesAsText("action");
        assertTrue(actions.containsAll(List.of("HEALTH_NUMBER_SET", "MEDICAL_ITEM_ADDED", "NOTE_CREATED", "DOCUMENT_UPLOADED")));

        for (Map<String, Object> row : auditRows(p)) {
            String all = row.toString();
            assertFalse(all.contains(RAMQ), all);
            assertFalse(all.contains(NOTE_BODY), all);
            assertFalse(all.contains(FILENAME), all);
            assertFalse(all.contains("Peanut"), all);
            assertFalse(all.contains("Secret title"), all);
        }
    }

    @Test
    void pagingAndViewAuditOnlyOnFirstPage() throws Exception {
        long p = newPatient();
        for (int i = 0; i < 5; i++) {
            clock.plus(Duration.ofMinutes(1));
            call(post("/api/patients/" + p + "/allergies").contentType(MediaType.APPLICATION_JSON).content("{\"substance\":\"A" + i + "\",\"severity\":\"MILD\"}"));
        }
        assertEquals(0, auditCount(p, "AUDIT_LOG_VIEWED"));
        JsonNode p0 = json(call(get("/api/patients/" + p + "/audit").param("size", "2")));
        assertEquals(2, p0.get("items").size());
        assertTrue(p0.get("hasMore").asBoolean());
        assertEquals(50, json(call(get("/api/patients/" + p + "/audit"))).get("size").asInt());
        assertEquals(2, auditCount(p, "AUDIT_LOG_VIEWED"));
        JsonNode p1 = json(call(get("/api/patients/" + p + "/audit").param("size", "2").param("page", "1")));
        assertEquals(1, p1.get("page").asInt());
        assertEquals(2, auditCount(p, "AUDIT_LOG_VIEWED"), "page 1 is not audited");
        assertEquals(400, call(get("/api/patients/" + p + "/audit").param("size", "101")).getResponse().getStatus());
        assertEquals(404, call(get("/api/patients/987654321/audit")).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where patient_id = 987654321", Long.class));
    }

    @Test
    void auditRowsSurvivePatientDeletion() throws Exception {
        long p = newPatient();
        call(put("/api/patients/" + p).contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"Changed","email":"t@example.com","phone":"514-555-0100","gender":"female",
                 "address":{"city":"Montreal","state":"QC","zip":"H2X 1Y4"}}"""));
        assertEquals(204, call(delete("/api/patients/" + p)).getResponse().getStatus());
        assertEquals(1, auditCount(p, "PATIENT_UPDATED"));
        assertEquals(1, auditCount(p, "PATIENT_DELETED"));
    }
}
