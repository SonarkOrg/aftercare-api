package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class AuditFilterTests extends IntegrationTest {

    private long insertAudit(String actorSub, String action, Long patientId, String at) {
        jdbc.update("insert into audit_events (occurred_at, actor_sub, actor_name, action, patient_id, resource_type, resource_id, detail) "
                + "values (?, ?, ?, ?, ?, 'PATIENT', ?, null)", Timestamp.from(Instant.parse(at)), actorSub, "Name " + actorSub,
                action, patientId, patientId);
        return jdbc.queryForObject("select max(id) from audit_events", Long.class);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asLong()));
        return out;
    }

    @Test
    void perPatientLogFiltersByActor() throws Exception {
        long p = newPatient();
        String u = uniq();
        ensureStaff("auth0|aud-" + u, "Auditor " + u, "NURSE", true);
        String body = "{\"firstName\":\"Changed\",\"lastName\":\"Patient\",\"email\":\"t@example.com\",\"phone\":\"514-555-0100\",\"gender\":\"female\","
                + "\"address\":{\"city\":\"Montreal\",\"state\":\"QC\",\"zip\":\"H2X 1Y4\"}}";
        assertEquals(200, as(staffAs("auth0|aud-" + u), put("/api/patients/" + p).contentType(MediaType.APPLICATION_JSON).content(body)).getResponse().getStatus());
        assertEquals(200, as(asRole(AccessRole.ADMIN), put("/api/patients/" + p).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("Changed", "Again"))).getResponse().getStatus());

        JsonNode all = json(as(asRole(AccessRole.ADMIN), get("/api/patients/" + p + "/audit")));
        assertTrue(all.get("items").size() >= 2);
        JsonNode only = json(as(asRole(AccessRole.ADMIN), get("/api/patients/" + p + "/audit").param("actorSub", "auth0|aud-" + u)));
        assertEquals(1, only.get("items").size());
        assertEquals("auth0|aud-" + u, only.get("items").get(0).get("actorSub").asText());
        assertEquals(p, only.get("items").get(0).get("patientId").asLong());
        assertEquals(0, json(as(asRole(AccessRole.ADMIN), get("/api/patients/" + p + "/audit").param("actorSub", "nobody"))).get("items").size());
        // exact match, max 255
        MvcResult long256 = as(asRole(AccessRole.ADMIN), get("/api/patients/" + p + "/audit").param("actorSub", "x".repeat(256)));
        assertEquals(400, long256.getResponse().getStatus());
        assertTrue(json(long256).get("fieldErrors").has("actorSub"));
        assertEquals(403, as(asRole(AccessRole.NURSE), get("/api/patients/" + p + "/audit")).getResponse().getStatus());
    }

    @Test
    void globalLogFiltersAndOrder() throws Exception {
        String u = uniq();
        String a = "aud-a-" + u;
        String b = "aud-b-" + u;
        long p1 = newPatient();
        long p2 = newPatient();
        long r1 = insertAudit(a, "PATIENT_UPDATED", p1, "2026-10-01T12:00:00Z");
        long r2 = insertAudit(a, "NOTE_CREATED", p2, "2026-10-02T12:00:00Z");
        long r3 = insertAudit(b, "PATIENT_UPDATED", p2, "2026-10-03T12:00:00Z");
        long old = insertAudit(a, "PATIENT_UPDATED", p1, "2026-08-01T12:00:00Z");

        // default window: the last 30 days (today is 2026-10-14), so the August row is out
        JsonNode byActor = json(as(asRole(AccessRole.ADMIN), get("/api/audit").param("actorSub", a)));
        assertEquals(List.of(r2, r1), ids(byActor)); // newest first
        assertEquals(50, byActor.get("size").asInt());
        assertFalse(byActor.get("hasMore").asBoolean());
        JsonNode first = byActor.get("items").get(0);
        assertEquals(p2, first.get("patientId").asLong());
        assertEquals("NOTE_CREATED", first.get("action").asText());
        assertEquals("Name " + a, first.get("actorName").asText());
        assertEquals(List.of("id", "occurredAt", "actorName", "actorSub", "action", "resourceType", "resourceId", "patientId", "detail"),
                fieldNames(first));

        assertEquals(List.of(r1), ids(json(as(asRole(AccessRole.ADMIN), get("/api/audit").param("actorSub", a).param("action", "PATIENT_UPDATED")))));
        assertEquals(List.of(r3, r2), ids(json(as(asRole(AccessRole.ADMIN), get("/api/audit").param("patientId", String.valueOf(p2))
                .param("from", "2026-10-02").param("to", "2026-10-03")))));
        // explicit range reaches the August row
        assertEquals(List.of(r2, r1, old), ids(json(as(asRole(AccessRole.ADMIN), get("/api/audit").param("actorSub", a)
                .param("from", "2026-07-01").param("to", "2026-10-14")))));
        // inclusive day bounds in Toronto time
        assertEquals(List.of(r1), ids(json(as(asRole(AccessRole.ADMIN), get("/api/audit").param("actorSub", a)
                .param("from", "2026-10-01").param("to", "2026-10-01")))));
        // paging
        JsonNode page = json(as(asRole(AccessRole.ADMIN), get("/api/audit").param("actorSub", a).param("size", "1")));
        assertEquals(List.of(r2), ids(page));
        assertTrue(page.get("hasMore").asBoolean());
        assertEquals(List.of(r1), ids(json(as(asRole(AccessRole.ADMIN), get("/api/audit").param("actorSub", a).param("size", "1").param("page", "1")))));
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void rangeLimitAndValidation() throws Exception {
        MvcResult tooWide = as(asRole(AccessRole.ADMIN), get("/api/audit").param("from", "2026-01-01").param("to", "2027-01-02"));
        assertEquals(400, tooWide.getResponse().getStatus());
        assertEquals("range must not exceed 366 days", json(tooWide).get("fieldErrors").get("to").asText());
        assertEquals(200, as(asRole(AccessRole.ADMIN), get("/api/audit").param("from", "2026-01-01").param("to", "2027-01-01")).getResponse().getStatus());
        assertEquals(400, as(asRole(AccessRole.ADMIN), get("/api/audit").param("from", "2026-02-01").param("to", "2026-01-01")).getResponse().getStatus());
        assertEquals(400, as(asRole(AccessRole.ADMIN), get("/api/audit").param("action", "NOT_AN_ACTION")).getResponse().getStatus());
        assertEquals(400, as(asRole(AccessRole.ADMIN), get("/api/audit").param("from", "yesterday")).getResponse().getStatus());
        assertEquals(400, as(asRole(AccessRole.ADMIN), get("/api/audit").param("size", "101")).getResponse().getStatus());
        assertEquals(400, as(asRole(AccessRole.ADMIN), get("/api/audit").param("page", "-1")).getResponse().getStatus());
    }

    @Test
    void firstPageIsAuditedWithFilterNamesOnly() throws Exception {
        String secret = "secret-actor-" + uniq();
        assertEquals(200, as(asRole(AccessRole.ADMIN), get("/api/audit").param("actorSub", secret).param("from", "2026-10-01").param("to", "2026-10-02")).getResponse().getStatus());
        var row = jdbc.queryForList("select patient_id, resource_type, detail, actor_sub from audit_events where action = 'AUDIT_LOG_VIEWED' and patient_id is null order by id desc limit 1").get(0);
        assertNull(row.get("patient_id"));
        assertEquals("AUDIT_LOG", row.get("resource_type"));
        assertEquals("actorSub,from,to", row.get("detail"));
        assertEquals(SUB_ADMIN, row.get("actor_sub"));
        // values are never recorded; page 1 is not audited; no filters means no detail
        long before = jdbc.queryForObject("select count(*) from audit_events where action = 'AUDIT_LOG_VIEWED'", Long.class);
        as(asRole(AccessRole.ADMIN), get("/api/audit").param("page", "1"));
        assertEquals(before, jdbc.queryForObject("select count(*) from audit_events where action = 'AUDIT_LOG_VIEWED'", Long.class));
        as(asRole(AccessRole.ADMIN), get("/api/audit"));
        assertNull(jdbc.queryForList("select detail from audit_events where action = 'AUDIT_LOG_VIEWED' order by id desc", String.class).get(0));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where detail like ?", Integer.class, "%" + secret + "%"));
    }

    @Test
    void nonAdminsAreForbidden() throws Exception {
        for (AccessRole role : List.of(AccessRole.PHYSICIAN, AccessRole.NURSE, AccessRole.RECEPTION)) {
            MvcResult r = as(asRole(role), get("/api/audit"));
            assertEquals(403, r.getResponse().getStatus(), role.name());
            assertEquals("FORBIDDEN", json(r).get("error").asText());
        }
    }
}
