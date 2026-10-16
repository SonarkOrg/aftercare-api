package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What RECEPTION must never receive, enforced on the server, plus the audit trail of denials. */
class RedactionTests extends IntegrationTest {

    private JsonNode summaryOf(JsonNode list, long id) {
        for (JsonNode p : list) {
            if (p.get("id").asLong() == id) {
                return p;
            }
        }
        throw new AssertionError("patient " + id + " not listed");
    }

    @Test
    void patientListHidesFollowUpAndAlertCountFromReception() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        mockMvc.perform(post("/api/public/q/" + f.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(8, 38.4, true))).andExpect(status().isCreated());

        JsonNode nurse = summaryOf(json(as(asRole(AccessRole.NURSE), get("/api/patients"))), p);
        assertFalse(nurse.get("activeFollowUp").isNull());
        assertTrue(nurse.get("unresolvedAlertCount").asInt() >= 1);

        JsonNode reception = summaryOf(json(as(asRole(AccessRole.RECEPTION), get("/api/patients"))), p);
        assertTrue(reception.has("activeFollowUp"));
        assertTrue(reception.get("activeFollowUp").isNull());
        assertTrue(reception.has("unresolvedAlertCount"));
        assertTrue(reception.get("unresolvedAlertCount").isNull());
        assertEquals("Test", reception.get("firstName").asText()); // demographics stay
    }

    @Test
    void appointmentDetailHidesIntakeButKeepsFlagCountAndStatus() throws Exception {
        long p = newPatient();
        JsonNode created = newAppointment(p, "SURGERY", "ORTHOPEDIC_SURGERY", "2026-10-20T08:00:00");
        mockMvc.perform(post("/api/public/q/" + created.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(intakeBody(SURGERY_URL, true, true, true))).andExpect(status().isCreated());
        long id = created.get("appointment").get("id").asLong();

        JsonNode nurse = json(as(asRole(AccessRole.NURSE), get("/api/appointments/" + id)));
        assertFalse(nurse.get("intake").isNull());
        assertFalse(nurse.get("intakeRedacted").asBoolean());
        assertTrue(nurse.get("flagCount").asInt() > 0);

        JsonNode reception = json(as(asRole(AccessRole.RECEPTION), get("/api/appointments/" + id)));
        assertTrue(reception.get("intake").isNull());
        assertTrue(reception.get("intakeRedacted").asBoolean());
        assertEquals(nurse.get("flagCount").asInt(), reception.get("flagCount").asInt());
        assertEquals("NEEDS_REVIEW", reception.get("status").asText());
        assertFalse(reception.get("intakeSubmittedAt").isNull());
        // without a submitted intake the flag is still true for reception (the caller lacks CLINICAL_READ), false for a nurse
        JsonNode bare = newAppointment(p, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-21T08:00:00");
        long bareId = bare.get("appointment").get("id").asLong();
        assertTrue(json(as(asRole(AccessRole.RECEPTION), get("/api/appointments/" + bareId))).get("intakeRedacted").asBoolean());
        assertFalse(json(as(asRole(AccessRole.NURSE), get("/api/appointments/" + bareId))).get("intakeRedacted").asBoolean());
    }

    @Test
    void notificationHistoryShowsReceptionAppointmentRowsOnly() throws Exception {
        long p = newPatient();
        Instant at = Instant.parse("2026-10-13T12:00:00Z");
        insertOutbox(p, "FOLLOW_UP_LINK", "SENT", true, at, 1L, null, null);
        insertOutbox(p, "CHECKIN_REMINDER", "SENT", true, at.plusSeconds(1), 1L, null, null);
        insertOutbox(p, "APPOINTMENT_LINK", "SENT", true, at.plusSeconds(2), null, 2L, null);
        insertOutbox(p, "APPOINTMENT_REMINDER", "SENT", true, at.plusSeconds(3), null, 2L, null);

        JsonNode all = json(as(asRole(AccessRole.NURSE), get("/api/patients/" + p + "/notifications"))).get("items");
        assertEquals(4, all.size());
        JsonNode reception = json(as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p + "/notifications"))).get("items");
        assertEquals(2, reception.size());
        for (JsonNode n : reception) {
            assertTrue(n.get("template").asText().startsWith("APPOINTMENT_"), n.toString());
        }
    }

    @Test
    void demographicsViewByReceptionIsAuditedButNotByClinicians() throws Exception {
        long p = newPatient();
        assertEquals(200, as(asRole(AccessRole.NURSE), get("/api/patients/" + p)).getResponse().getStatus());
        assertEquals(0, auditCount(p, "PATIENT_FILE_VIEWED"));
        assertEquals(200, as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p)).getResponse().getStatus());
        assertEquals(1, auditCount(p, "PATIENT_FILE_VIEWED"));
        Map<String, Object> row = jdbc.queryForMap("select actor_sub, resource_type, resource_id, detail from audit_events where patient_id = ? and action = 'PATIENT_FILE_VIEWED'", p);
        assertEquals(SUB_RECEPTION, row.get("actor_sub"));
        assertEquals("PATIENT", row.get("resource_type"));
        assertEquals(p, ((Number) row.get("resource_id")).longValue());
        assertEquals("demographics", row.get("detail"));
        // an unknown patient is never audited as a view
        long before = jdbc.queryForObject("select count(*) from audit_events where action = 'PATIENT_FILE_VIEWED'", Long.class);
        assertEquals(404, as(asRole(AccessRole.RECEPTION), get("/api/patients/987654321")).getResponse().getStatus());
        assertEquals(before, jdbc.queryForObject("select count(*) from audit_events where action = 'PATIENT_FILE_VIEWED'", Long.class));
    }

    @Test
    void forbiddenIsAuditedAsAccessDeniedAndNotStaffIsNot() throws Exception {
        long p = newPatient();
        var denied = as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p + "/medical"));
        assertEquals(403, denied.getResponse().getStatus());
        assertEquals("FORBIDDEN", json(denied).get("error").asText());
        assertEquals(1, auditCount(p, "ACCESS_DENIED"));
        Map<String, Object> row = jdbc.queryForMap("select actor_sub, actor_name, resource_type, resource_id, detail from audit_events where patient_id = ? and action = 'ACCESS_DENIED'", p);
        assertEquals("GET /api/patients/*/medical", row.get("detail"));
        assertEquals(SUB_RECEPTION, row.get("actor_sub"));
        assertEquals("Test Reception", row.get("actor_name"));
        assertEquals("ENDPOINT", row.get("resource_type"));
        assertNull(row.get("resource_id"));

        // NOT_STAFF is not audited
        assertEquals(403, as(UNLINKED, get("/api/patients/" + p + "/medical")).getResponse().getStatus());
        assertEquals(403, as(staffAs(SUB_INACTIVE), get("/api/patients/" + p + "/medical")).getResponse().getStatus());
        assertEquals(1, auditCount(p, "ACCESS_DENIED"));

        // endpoints without a patient in the path: patientId null
        long before = jdbc.queryForObject("select count(*) from audit_events where action = 'ACCESS_DENIED' and patient_id is null", Long.class);
        assertEquals(403, as(asRole(AccessRole.NURSE), get("/api/audit")).getResponse().getStatus());
        assertEquals(before + 1, jdbc.queryForObject("select count(*) from audit_events where action = 'ACCESS_DENIED' and patient_id is null", Long.class));
        List<String> details = jdbc.queryForList("select detail from audit_events where action = 'ACCESS_DENIED' and patient_id is null order by id desc", String.class);
        assertEquals("GET /api/audit", details.get(0));
        // the deny-by-default rule is audited with the catch-all pattern
        assertEquals(403, as(asRole(AccessRole.ADMIN), get("/api/not-a-route")).getResponse().getStatus());
        details = jdbc.queryForList("select detail from audit_events where action = 'ACCESS_DENIED' and patient_id is null order by id desc", String.class);
        assertEquals("GET /api/**", details.get(0));
    }

    @Test
    void appointmentSpecialtyIsHiddenFromReceptionLikeTheActiveFollowUp() throws Exception {
        long p = newPatient();
        JsonNode created = newAppointment(p, "SURGERY", "ORTHOPEDIC_SURGERY", "2026-10-22T08:00:00");
        long id = created.get("appointment").get("id").asLong();
        assertEquals("ORTHOPEDIC_SURGERY", created.get("appointment").get("specialty").asText());
        for (AccessRole clinical : List.of(AccessRole.NURSE, AccessRole.PHYSICIAN, AccessRole.ADMIN)) {
            assertEquals("ORTHOPEDIC_SURGERY", json(as(asRole(clinical), get("/api/appointments/" + id))).get("specialty").asText(), clinical.name());
        }
        JsonNode detail = json(as(asRole(AccessRole.RECEPTION), get("/api/appointments/" + id)));
        assertTrue(detail.has("specialty"));
        assertTrue(detail.get("specialty").isNull());
        assertEquals("SURGERY", detail.get("type").asText());
        boolean listed = false;
        for (JsonNode a : json(as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p + "/appointments")))) {
            if (a.get("id").asLong() == id) {
                listed = true;
                assertTrue(a.has("specialty") && a.get("specialty").isNull());
            }
        }
        assertTrue(listed, "listed for reception");
    }

    @Test
    void identicalAccessDeniedEventsAreAuditedOncePerMinute() throws Exception {
        long p = newPatient();
        for (int i = 0; i < 5; i++) {
            assertEquals(403, as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p + "/medical")).getResponse().getStatus());
        }
        assertEquals(1, auditCount(p, "ACCESS_DENIED"));
        // another path is a different event
        assertEquals(403, as(asRole(AccessRole.RECEPTION), post("/api/patients/" + p + "/allergies")).getResponse().getStatus());
        assertEquals(2, auditCount(p, "ACCESS_DENIED"));
        // after the window the same event is audited again
        clock.plus(java.time.Duration.ofSeconds(59));
        as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p + "/medical"));
        assertEquals(2, auditCount(p, "ACCESS_DENIED"));
        clock.plus(java.time.Duration.ofSeconds(2));
        as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p + "/medical"));
        assertEquals(3, auditCount(p, "ACCESS_DENIED"));
    }

}
