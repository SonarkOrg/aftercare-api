package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class NoteTimelineTests extends IntegrationTest {

    private JsonNode createNote(long p, String body, String notedAt) throws Exception {
        String json = "{\"body\":" + om.writeValueAsString(body) + ",\"notedAt\":" + (notedAt == null ? "null" : "\"" + notedAt + "\"") + "}";
        MvcResult r = call(post("/api/patients/" + p + "/notes").contentType(MediaType.APPLICATION_JSON).content(json));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return json(r);
    }

    private MvcResult edit(Object who, long noteId, String body) throws Exception {
        var b = put("/api/notes/" + noteId).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}");
        return who == null ? call(b) : as((org.springframework.test.web.servlet.request.RequestPostProcessor) who, b);
    }

    private MvcResult del(Object who, long noteId, String reason) throws Exception {
        var b = post("/api/notes/" + noteId + "/delete").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
        return who == null ? call(b) : as((org.springframework.test.web.servlet.request.RequestPostProcessor) who, b);
    }

    @Test
    void createAppearsInTimelineWithEmbeddedNote() throws Exception {
        long p = newPatient();
        JsonNode n = createNote(p, "Called patient", null);
        assertTrue(n.get("editableByMe").asBoolean());
        assertTrue(n.get("deletableByMe").asBoolean());
        assertEquals(Instant.parse(n.get("createdAt").asText()).plus(Duration.ofMinutes(15)), Instant.parse(n.get("editableUntil").asText()));
        JsonNode page = json(call(get("/api/patients/" + p + "/timeline")));
        JsonNode item = page.get("items").get(0);
        assertEquals("NOTE_ADDED", item.get("kind").asText());
        assertEquals("NOTE", item.get("category").asText());
        assertEquals("Note", item.get("summary").asText());
        assertEquals("Called patient", item.get("note").get("body").asText());
        assertTrue(item.get("note").get("editableByMe").asBoolean());
        assertEquals(1, auditCount(p, "NOTE_CREATED"));
        // another viewer cannot edit
        JsonNode other = json(as(staffAs("auth0|other"), get("/api/patients/" + p + "/timeline"))).get("items").get(0);
        assertFalse(other.get("note").get("editableByMe").asBoolean());
    }

    @Test
    void editWindowIsFifteenMinutes() throws Exception {
        long p = newPatient();
        long inside = createNote(p, "one", null).get("id").asLong();
        long outside = createNote(p, "two", null).get("id").asLong();
        clock.plus(Duration.ofMinutes(14));
        MvcResult ok = edit(null, inside, "one edited");
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals("one edited", json(ok).get("body").asText());
        assertFalse(json(ok).get("editedAt").isNull());
        clock.plus(Duration.ofMinutes(2));
        MvcResult late = edit(null, outside, "too late");
        assertEquals(409, late.getResponse().getStatus());
        assertEquals("EDIT_WINDOW_CLOSED", json(late).get("error").asText());
        assertEquals("Notes can only be edited within 15 minutes", json(late).get("message").asText());
        // still deletable after the window
        assertEquals(200, del(null, outside, "entered in error").getResponse().getStatus());
    }

    @Test
    void nonAuthorGets403() throws Exception {
        long p = newPatient();
        long id = createNote(p, "mine", null).get("id").asLong();
        MvcResult e = edit(staffAs("auth0|someone-else"), id, "x");
        assertEquals(403, e.getResponse().getStatus());
        assertEquals("NOT_AUTHOR", json(e).get("error").asText());
        assertEquals("Only the author can change this note", json(e).get("message").asText());
        assertEquals(403, del(staffAs("auth0|someone-else"), id, "because").getResponse().getStatus());
    }

    @Test
    void deleteIsSoftAndOnlyOnce() throws Exception {
        long p = newPatient();
        long id = createNote(p, "Secret body 42", null).get("id").asLong();
        MvcResult r = del(null, id, "wrong patient");
        assertEquals(200, r.getResponse().getStatus());
        JsonNode d = json(r);
        assertTrue(d.get("body").isNull());
        assertTrue(d.get("deleted").asBoolean());
        assertEquals("wrong patient", d.get("deleteReason").asText());
        assertFalse(d.get("editableByMe").asBoolean());
        assertEquals("Secret body 42", jdbc.queryForObject("select body from care_notes where id = ?", String.class, id));
        MvcResult again = del(null, id, "again please");
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("INVALID_STATE", json(again).get("error").asText());
        assertEquals(409, edit(null, id, "x").getResponse().getStatus());
        // still on the timeline, flagged deleted, body hidden
        JsonNode item = json(call(get("/api/patients/" + p + "/timeline"))).get("items").get(0);
        assertTrue(item.get("note").get("deleted").asBoolean());
        assertTrue(item.get("note").get("body").isNull());
        assertEquals(400, del(null, id, "no").getResponse().getStatus());
    }

    @Test
    void notedAtWindowAndValidation() throws Exception {
        long p = newPatient();
        String old = clock.instant().minus(Duration.ofDays(8)).toString();
        MvcResult r = call(post("/api/patients/" + p + "/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"x\",\"notedAt\":\"" + old + "\"}"));
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("must be within the last 7 days", json(r).get("fieldErrors").get("notedAt").asText());
        String earlier = clock.instant().minus(Duration.ofDays(2)).toString();
        JsonNode ok = createNote(p, "backdated", earlier);
        assertEquals(earlier, ok.get("notedAt").asText());
        assertEquals(404, call(post("/api/patients/987654321/notes").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}")).getResponse().getStatus());
        assertEquals(400, call(post("/api/patients/" + p + "/notes").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"   \"}")).getResponse().getStatus());
        assertEquals(400, call(post("/api/patients/" + p + "/notes").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + "a".repeat(5001) + "\"}")).getResponse().getStatus());
    }

    @Test
    void timelineMergesAllSourcesNewestFirst() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        clock.plus(Duration.ofMinutes(1));
        assertEquals(201, mockMvc.perform(post("/api/public/q/" + f.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(8, null, false))).andReturn().getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));
        long alertId = json(call(get("/api/patients/" + p + "/alerts"))).get(0).get("id").asLong();
        assertEquals(200, call(post("/api/alerts/" + alertId + "/acknowledge")).getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));
        long appt = newAppointment(p, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-16T08:00:00").get("appointment").get("id").asLong();
        clock.plus(Duration.ofMinutes(1));
        assertEquals(200, call(post("/api/appointments/" + appt + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}")).getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));
        assertEquals(201, call(multipart("/api/patients/" + p + "/documents", pdfFile("a.pdf"), "CONSENT", "Consent form")).getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));
        assertEquals(201, call(post("/api/patients/" + p + "/messages").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Hello\"}")).getResponse().getStatus());
        clock.plus(Duration.ofMinutes(1));
        createNote(p, "A note", null);

        JsonNode items = json(call(get("/api/patients/" + p + "/timeline"))).get("items");
        Set<String> kinds = new HashSet<>();
        Instant prev = null;
        for (JsonNode i : items) {
            kinds.add(i.get("kind").asText());
            Instant at = Instant.parse(i.get("occurredAt").asText());
            if (prev != null) {
                assertFalse(at.isAfter(prev), "ordered newest first");
            }
            prev = at;
        }
        assertEquals(Set.of("FOLLOW_UP_STARTED", "CHECK_IN_SUBMITTED", "ALERT_CREATED", "ALERT_ACKNOWLEDGED", "APPOINTMENT_CREATED",
                "APPOINTMENT_STATUS_CHANGED", "DOCUMENT_UPLOADED", "MESSAGE_TO_PATIENT", "NOTE_ADDED"), kinds);
        assertEquals("NOTE_ADDED", items.get(0).get("kind").asText());
        JsonNode created = null;
        JsonNode status = null;
        JsonNode doc = null;
        for (JsonNode i : items) {
            if (i.get("kind").asText().equals("ALERT_CREATED")) created = i;
            if (i.get("kind").asText().equals("APPOINTMENT_STATUS_CHANGED")) status = i;
            if (i.get("kind").asText().equals("DOCUMENT_UPLOADED")) doc = i;
        }
        assertEquals("HIGH", created.get("severity").asText());
        assertEquals("SYSTEM", created.get("actorType").asText());
        assertTrue(created.get("summary").asText().startsWith("High alert: "));
        assertTrue(status.get("summary").asText().contains("General consult on 2026-10-16: Scheduled → Cancelled"), status.get("summary").asText());
        assertEquals("Document uploaded: Consent form (Consent)", doc.get("summary").asText());

        // category filter
        JsonNode alerts = json(call(get("/api/patients/" + p + "/timeline").param("category", "ALERT"))).get("items");
        assertEquals(2, alerts.size());
        alerts.forEach(a -> assertEquals("ALERT", a.get("category").asText()));
        JsonNode two = json(call(get("/api/patients/" + p + "/timeline").param("category", "ALERT").param("category", "NOTE"))).get("items");
        assertEquals(3, two.size());

        // paging
        JsonNode p0 = json(call(get("/api/patients/" + p + "/timeline").param("size", "4")));
        assertEquals(4, p0.get("items").size());
        assertTrue(p0.get("hasMore").asBoolean());
        assertEquals(0, p0.get("page").asInt());
        List<Long> seen = new ArrayList<>();
        int page = 0;
        while (true) {
            JsonNode pg = json(call(get("/api/patients/" + p + "/timeline").param("size", "4").param("page", "" + page)));
            pg.get("items").forEach(i -> seen.add(i.get("id").asLong()));
            if (!pg.get("hasMore").asBoolean()) break;
            page++;
        }
        assertEquals(items.size(), seen.size());
        assertEquals(seen.size(), new HashSet<>(seen).size());
    }

    @Test
    void timelineParamValidation() throws Exception {
        long p = newPatient();
        MvcResult r = call(get("/api/patients/" + p + "/timeline").param("category", "NOPE"));
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(json(r).get("fieldErrors").has("category"));
        assertEquals(400, call(get("/api/patients/" + p + "/timeline").param("size", "0")).getResponse().getStatus());
        assertEquals(400, call(get("/api/patients/" + p + "/timeline").param("size", "101")).getResponse().getStatus());
        assertEquals(400, call(get("/api/patients/" + p + "/timeline").param("page", "-1")).getResponse().getStatus());
        assertEquals(404, call(get("/api/patients/987654321/timeline")).getResponse().getStatus());
    }
}
