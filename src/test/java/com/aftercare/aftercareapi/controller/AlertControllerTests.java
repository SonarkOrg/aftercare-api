package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.AlertStatus;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertControllerTests extends IntegrationTest {

    /** Patient with one check-in: pain HIGH, temperature HIGH, redness MEDIUM. */
    private long patientWithAlerts() throws Exception {
        long pid = newPatient();
        JsonNode f = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        mockMvc.perform(post("/api/public/q/" + f.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(8, 38.4, true))).andExpect(status().isCreated());
        return pid;
    }

    private JsonNode forPatient(long pid) throws Exception {
        return json(call(get("/api/patients/" + pid + "/alerts")));
    }

    private MvcResult resolve(long id, String note) throws Exception {
        return call(post("/api/alerts/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"note\":" + om.writeValueAsString(note) + "}"));
    }

    @Test
    void defaultListExcludesResolvedAndSortOrderHolds() throws Exception {
        long pid = patientWithAlerts();
        JsonNode alerts = forPatient(pid);
        long pain = alerts.get(0).get("id").asLong();
        long temp = alerts.get(1).get("id").asLong();
        long redness = alerts.get(2).get("id").asLong();
        assertEquals(200, call(post("/api/alerts/" + pain + "/acknowledge")).getResponse().getStatus());
        assertEquals(200, resolve(temp, "called patient").getResponse().getStatus());

        JsonNode all = forPatient(pid);
        assertEquals(List.of("OPEN", "ACKNOWLEDGED", "RESOLVED"), List.of(all.get(0).get("status").asText(), all.get(1).get("status").asText(), all.get(2).get("status").asText()));
        assertEquals(redness, all.get(0).get("id").asLong());

        JsonNode def = json(call(get("/api/alerts")));
        for (JsonNode a : def) {
            assertNotEquals("RESOLVED", a.get("status").asText());
        }
        // global ordering: status enum order, severity (HIGH first), createdAt, then id
        for (int i = 1; i < def.size(); i++) {
            JsonNode x = def.get(i - 1);
            JsonNode y = def.get(i);
            int c = AlertStatus.valueOf(x.get("status").asText()).compareTo(AlertStatus.valueOf(y.get("status").asText()));
            if (c == 0) {
                c = AlertSeverity.valueOf(x.get("severity").asText()).compareTo(AlertSeverity.valueOf(y.get("severity").asText()));
            }
            if (c == 0) {
                c = Instant.parse(x.get("createdAt").asText()).compareTo(Instant.parse(y.get("createdAt").asText()));
            }
            if (c == 0) {
                c = Long.compare(x.get("id").asLong(), y.get("id").asLong());
                assertTrue(c < 0, "id tie-break within equal status/severity/createdAt");
            }
            assertTrue(c <= 0, "alerts out of order at index " + i);
        }
        JsonNode resolved = json(call(get("/api/alerts?status=RESOLVED")));
        assertTrue(resolved.size() >= 1);
        for (JsonNode a : resolved) {
            assertEquals("RESOLVED", a.get("status").asText());
        }
        MvcResult foo = call(get("/api/alerts?status=FOO"));
        assertEquals(400, foo.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(foo).get("error").asText());
        int mine = 0;
        for (JsonNode n : json(call(get("/api/alerts?status=OPEN&status=ACKNOWLEDGED")))) {
            if (n.get("patientId").asLong() == pid) {
                mine++;
            }
        }
        assertEquals(2, mine);
    }

    @Test
    void acknowledge() throws Exception {
        long pid = patientWithAlerts();
        long id = forPatient(pid).get(0).get("id").asLong();
        JsonNode a = json(call(post("/api/alerts/" + id + "/acknowledge")));
        assertEquals("ACKNOWLEDGED", a.get("status").asText());
        assertEquals("auth0|nurse-a", a.get("acknowledgedBy").asText());
        assertFalse(a.get("acknowledgedAt").isNull());
        MvcResult again = call(post("/api/alerts/" + id + "/acknowledge"));
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("INVALID_STATE", json(again).get("error").asText());
        assertEquals(404, call(post("/api/alerts/99999999/acknowledge")).getResponse().getStatus());
    }

    @Test
    void resolve() throws Exception {
        long pid = patientWithAlerts();
        long id = forPatient(pid).get(0).get("id").asLong();
        MvcResult blank = resolve(id, "   ");
        assertEquals(400, blank.getResponse().getStatus());
        assertTrue(json(blank).get("fieldErrors").has("note"));
        JsonNode r = json(resolve(id, "  Called the patient  "));
        assertEquals("RESOLVED", r.get("status").asText());
        assertEquals("auth0|nurse-a", r.get("resolvedBy").asText());
        assertEquals("Called the patient", r.get("resolutionNote").asText());
        assertTrue(r.get("acknowledgedAt").isNull());
        assertEquals(409, resolve(id, "again").getResponse().getStatus());
        assertEquals(409, call(post("/api/alerts/" + id + "/acknowledge")).getResponse().getStatus());
        long id2 = forPatient(pid).get(0).get("id").asLong();
        call(post("/api/alerts/" + id2 + "/acknowledge"));
        assertEquals(200, resolve(id2, "ok").getResponse().getStatus());
    }

    @Test
    void dtoFields() throws Exception {
        long pid = patientWithAlerts();
        JsonNode a = forPatient(pid).get(0);
        assertEquals("PAIN", a.get("linkId").asText());
        assertEquals("On a scale of 0 to 10, how bad is your pain today?", a.get("questionLabel").asText());
        assertEquals(a.get("questionLabelFr").asText().isBlank(), false);
        assertNotEquals(a.get("questionLabel").asText(), a.get("questionLabelFr").asText());
        assertEquals("8", a.get("answerValue").asText());
        assertEquals("HIGH", a.get("severity").asText());
        assertFalse(a.get("messageFr").asText().isBlank());
        assertEquals("Test", a.get("patientFirstName").asText());
        assertEquals("2026-10-14", a.get("checkInDate").asText());
        assertEquals(404, call(get("/api/patients/99999999/alerts")).getResponse().getStatus());
    }

    @Test
    void requireJwt() throws Exception {
        mockMvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/alerts/1/acknowledge")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/alerts/1/resolve").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/patients/1/alerts")).andExpect(status().isUnauthorized());
    }
}
