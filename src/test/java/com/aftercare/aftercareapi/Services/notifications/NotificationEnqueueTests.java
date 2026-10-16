package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class NotificationEnqueueTests extends IntegrationTest {

    private List<Map<String, Object>> rows(long patientId) {
        return jdbc.queryForList("select * from notification_outbox where patient_id = ? order by id", patientId);
    }

    private MvcResult sendLink(String kind, long id, String body) throws Exception {
        var b = post("/api/" + kind + "/" + id + "/send-link");
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(b);
    }

    private JsonNode error(MvcResult r, int status, String code) throws Exception {
        assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode e = json(r);
        assertEquals(code, e.get("error").asText());
        return e;
    }

    @Test
    void followUpCreationEnqueuesTheLinkOnlyWithConsentAndAnEnabledChannel() throws Exception {
        settings(true, false);
        long noConsent = newPatient();
        newFollowUp(noConsent, "VISCERAL_SURGERY", "2026-10-13", null);
        assertEquals(0, rows(noConsent).size());

        long withdrawn = newPatient();
        consent(withdrawn, "GRANTED", "EMAIL", null);
        consent(withdrawn, "WITHDRAWN", "EMAIL", null);
        newFollowUp(withdrawn, "VISCERAL_SURGERY", "2026-10-13", null);
        assertEquals(0, rows(withdrawn).size());

        long channelOff = newPatient();
        consent(channelOff, "GRANTED", "SMS", null); // SMS is disabled in settings
        newFollowUp(channelOff, "VISCERAL_SURGERY", "2026-10-13", null);
        assertEquals(0, rows(channelOff).size());

        long ok = newPatient();
        consent(ok, "GRANTED", "EMAIL", "EN");
        JsonNode f = newFollowUp(ok, "VISCERAL_SURGERY", "2026-10-13", null);
        List<Map<String, Object>> r = rows(ok);
        assertEquals(1, r.size());
        Map<String, Object> row = r.get(0);
        assertEquals("FOLLOW_UP_LINK", row.get("template_key"));
        assertEquals("EMAIL", row.get("channel"));
        assertEquals("EN", row.get("language"));
        assertEquals("PENDING", row.get("status"));
        assertEquals(true, row.get("automated"));
        assertEquals(0, ((Number) row.get("attempts")).intValue());
        assertEquals(f.get("followUp").get("id").asLong(), ((Number) row.get("follow_up_id")).longValue());
        assertNull(row.get("appointment_id"));
        assertNull(row.get("recipient_masked"), "set only after an attempt");
        assertEquals("system:notifications", row.get("created_by_sub"));
        long linkId = jdbc.queryForObject("select id from patient_access_links where follow_up_id = ? and revoked_at is null", Long.class, row.get("follow_up_id"));
        assertEquals("FOLLOW_UP_LINK:" + row.get("follow_up_id") + ":" + linkId, row.get("dedupe_key"));
        // the outbox has no column that could hold a body or a link
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'notification_outbox'", String.class);
        for (String c : columns) {
            assertFalse(c.contains("body") || c.contains("link") && !c.equals("follow_up_id") || c.contains("code") || c.contains("subject"), c);
        }
        // the follow-up creation itself was not affected
        assertEquals(1, json(call(get("/api/patients/" + ok + "/follow-ups"))).size());
    }

    @Test
    void appointmentCreationEnqueuesTheQuestionnaireLink() throws Exception {
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", "SMS", "FR");
        JsonNode a = newAppointment(p, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-28T09:00:00");
        List<Map<String, Object>> r = rows(p);
        assertEquals(1, r.size());
        assertEquals("APPOINTMENT_LINK", r.get(0).get("template_key"));
        assertEquals("SMS", r.get(0).get("channel"));
        assertEquals("FR", r.get(0).get("language"));
        assertEquals(a.get("appointment").get("id").asLong(), ((Number) r.get(0).get("appointment_id")).longValue());
        assertNull(r.get(0).get("follow_up_id"));

        long noConsent = newPatient();
        newAppointment(noConsent, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-28T10:00:00");
        assertEquals(0, rows(noConsent).size());
    }

    @Test
    void sendLinkChecksFailInTheContractedOrder() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        long fu = f.get("followUp").get("id").asLong();

        error(sendLink("follow-ups", fu, null), 409, "NO_CONSENT");
        assertEquals("The patient has not consented to notifications", json(sendLink("follow-ups", fu, "{}")).get("message").asText());

        consent(p, "GRANTED", "EMAIL", null);
        JsonNode disabled = error(sendLink("follow-ups", fu, null), 409, "CHANNEL_DISABLED");
        assertEquals("This notification channel is disabled", disabled.get("message").asText());

        settings(true, true);
        // an SMS recipient that cannot be normalised
        String body = "{\"firstName\":\"Test\",\"lastName\":\"Patient\",\"email\":\"t@example.com\",\"phone\":\"12345\",\"gender\":\"female\","
                + "\"address\":{\"city\":\"Montreal\",\"state\":\"QC\",\"zip\":\"H2X 1Y4\"}}";
        assertEquals(200, call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/patients/" + p)
                .contentType(MediaType.APPLICATION_JSON).content(body)).getResponse().getStatus());
        // changing the phone number withdrew the consent given for the old one: it must be given again
        error(sendLink("follow-ups", fu, "{\"channel\":\"SMS\"}"), 409, "NO_CONSENT");
        consent(p, "GRANTED", "EMAIL", null);
        JsonNode noRecipient = error(sendLink("follow-ups", fu, "{\"channel\":\"SMS\"}"), 409, "NO_RECIPIENT");
        assertEquals("The patient has no valid address for this channel", noRecipient.get("message").asText());

        // a link issued before codes were stored cannot be sent
        jdbc.update("update patient_access_links set code_enc = null where follow_up_id = ?", fu);
        JsonNode unavailable = error(sendLink("follow-ups", fu, "{\"channel\":\"EMAIL\"}"), 409, "LINK_UNAVAILABLE");
        assertEquals("Generate a new link before sending it", unavailable.get("message").asText());
        // regenerating fixes it
        assertEquals(200, call(post("/api/follow-ups/" + fu + "/access-code")).getResponse().getStatus());
        assertEquals(202, sendLink("follow-ups", fu, "{\"channel\":\"EMAIL\"}").getResponse().getStatus());
    }

    @Test
    void sendLinkReturns202WithTheNotificationAndAuditsTheRequest() throws Exception {
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", "SMS", "EN");
        long fu = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null).get("followUp").get("id").asLong();
        long automated = rows(p).size();
        assertEquals(1, automated);

        MvcResult r = sendLink("follow-ups", fu, null);
        assertEquals(202, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode n = json(r);
        assertEquals(List.of("id", "patientId", "followUpId", "appointmentId", "template", "channel", "language", "status", "automated",
                "attempts", "lastError", "recipientMasked", "createdAt", "sentAt", "requestedByName"), fieldNames(n));
        assertEquals(p, n.get("patientId").asLong());
        assertEquals(fu, n.get("followUpId").asLong());
        assertTrue(n.get("appointmentId").isNull());
        assertEquals("FOLLOW_UP_LINK", n.get("template").asText());
        assertEquals("SMS", n.get("channel").asText(), "no channel in the body means the patient's preference");
        assertEquals("EN", n.get("language").asText());
        assertEquals("PENDING", n.get("status").asText());
        assertFalse(n.get("automated").asBoolean());
        assertEquals(0, n.get("attempts").asInt());
        assertTrue(n.get("lastError").isNull());
        assertTrue(n.get("recipientMasked").isNull());
        assertTrue(n.get("sentAt").isNull());
        assertEquals("Nurse A", n.get("requestedByName").asText());

        // the explicit channel wins
        JsonNode viaEmail = json(sendLink("follow-ups", fu, "{\"channel\":\"EMAIL\"}"));
        assertEquals("EMAIL", viaEmail.get("channel").asText());

        Map<String, Object> audit = jdbc.queryForMap("select resource_type, resource_id, detail, patient_id from audit_events where action = 'NOTIFICATION_REQUESTED' and resource_id = ?", n.get("id").asLong());
        assertEquals("NOTIFICATION", audit.get("resource_type"));
        assertEquals("FOLLOW_UP_LINK/SMS", audit.get("detail"));
        assertEquals(p, ((Number) audit.get("patient_id")).longValue());
        // listing shows newest first with the automated row last
        JsonNode list = json(call(get("/api/patients/" + p + "/notifications"))).get("items");
        assertEquals(3, list.size());
        assertEquals(viaEmail.get("id").asLong(), list.get(0).get("id").asLong());
        assertTrue(list.get(2).get("automated").asBoolean());
        assertTrue(list.get(2).get("requestedByName").isNull());
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void theFourthManualSendWithin24HoursIsLimited() throws Exception {
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", "EMAIL", null);
        long fu = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null).get("followUp").get("id").asLong();
        for (int i = 0; i < 3; i++) {
            assertEquals(202, sendLink("follow-ups", fu, null).getResponse().getStatus(), "send " + (i + 1));
        }
        JsonNode e = error(sendLink("follow-ups", fu, null), 429, "NOTIFICATION_LIMIT");
        assertEquals("Too many notifications for this patient; try again later", e.get("message").asText());
        clock.plus(Duration.ofHours(25));
        assertEquals(202, sendLink("follow-ups", fu, null).getResponse().getStatus());
    }

    @Test
    void sendLinkStateChecks() throws Exception {
        settings(true, true);
        error(sendLink("follow-ups", 987654321L, null), 404, "NOT_FOUND");
        error(sendLink("appointments", 987654321L, null), 404, "NOT_FOUND");
        long p = newPatient();
        consent(p, "GRANTED", "EMAIL", null);
        long fu = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null).get("followUp").get("id").asLong();
        assertEquals(200, call(post("/api/follow-ups/" + fu + "/close")).getResponse().getStatus());
        JsonNode closed = error(sendLink("follow-ups", fu, null), 409, "INVALID_STATE");
        assertEquals("Follow-up is not active", closed.get("message").asText());

        JsonNode a = newAppointment(p, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-28T09:00:00");
        long ap = a.get("appointment").get("id").asLong();
        MvcResult ok = sendLink("appointments", ap, "{\"channel\":\"EMAIL\"}");
        assertEquals(202, ok.getResponse().getStatus());
        assertEquals("APPOINTMENT_LINK", json(ok).get("template").asText());
        assertEquals(ap, json(ok).get("appointmentId").asLong());
        assertTrue(json(ok).get("followUpId").isNull());
        assertEquals(200, call(post("/api/appointments/" + ap + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}")).getResponse().getStatus());
        JsonNode unusable = error(sendLink("appointments", ap, null), 409, "INVALID_STATE");
        assertEquals("Appointment link is not usable", unusable.get("message").asText());
        // reception may send the appointment link but not the follow-up link
        long ap2 = newAppointment(p, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-29T09:00:00").get("appointment").get("id").asLong();
        assertEquals(202, as(asRole(AccessRole.RECEPTION), post("/api/appointments/" + ap2 + "/send-link")).getResponse().getStatus());
        assertEquals(403, as(asRole(AccessRole.RECEPTION), post("/api/follow-ups/" + fu + "/send-link")).getResponse().getStatus());
    }

    @Test
    void automatedSendsAreCappedPerPatientPerDay() throws Exception {
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", "EMAIL", null);
        Instant now = clock.instant();
        for (int i = 0; i < 4; i++) {
            insertOutbox(p, "CHECKIN_REMINDER", "SENT", true, now.minus(Duration.ofHours(2 + i)), null, null, "cap-" + uniq());
        }
        newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        assertEquals(4, rows(p).size(), "no fifth automated row");
        // rows older than 24 hours and failed rows do not count
        long q = newPatient();
        consent(q, "GRANTED", "EMAIL", null);
        for (int i = 0; i < 4; i++) {
            insertOutbox(q, "CHECKIN_REMINDER", i % 2 == 0 ? "FAILED" : "SENT", true, now.minus(Duration.ofHours(i % 2 == 0 ? 2 : 30)), null, null, "cap-" + uniq());
        }
        newFollowUp(q, "VISCERAL_SURGERY", "2026-10-13", null);
        assertEquals(5, rows(q).size());
        // manual sends are not subject to the automated cap
        long fu = jdbc.queryForObject("select id from follow_ups where patient_id = ?", Long.class, p);
        assertEquals(202, sendLink("follow-ups", fu, null).getResponse().getStatus());
    }

    @Test
    void anAlreadyQueuedLinkIsNotQueuedTwice() throws Exception {
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", "EMAIL", null);
        long fu = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null).get("followUp").get("id").asLong();
        assertEquals(1, rows(p).size());
        long linkId = jdbc.queryForObject("select id from patient_access_links where follow_up_id = ? and revoked_at is null", Long.class, fu);
        assertFalse(notifications.enqueueAutomated(com.aftercare.aftercareapi.Enums.NotificationTemplate.FOLLOW_UP_LINK, p, fu, null,
                "FOLLOW_UP_LINK:" + fu + ":" + linkId));
        assertEquals(1, rows(p).size());
        // a regenerated link has a new id, so a fresh automated message is allowed
        assertTrue(notifications.enqueueAutomated(com.aftercare.aftercareapi.Enums.NotificationTemplate.FOLLOW_UP_LINK, p, fu, null,
                "FOLLOW_UP_LINK:" + fu + ":" + (linkId + 1000)));
        assertEquals(2, rows(p).size());
    }

    @org.springframework.beans.factory.annotation.Autowired
    NotificationService notifications;

    @Test
    void deletingAPatientRemovesTheirOutboxRows() throws Exception {
        long p = newPatient();
        insertOutbox(p, "FOLLOW_UP_LINK", "SENT", true, clock.instant(), null, null, null);
        insertOutbox(p, "CHECKIN_REMINDER", "PENDING", true, clock.instant(), null, null, null);
        long other = newPatient();
        insertOutbox(other, "FOLLOW_UP_LINK", "SENT", true, clock.instant(), null, null, null);
        assertEquals(204, as(asRole(AccessRole.ADMIN), delete("/api/patients/" + p)).getResponse().getStatus());
        assertEquals(0, rows(p).size());
        assertEquals(1, rows(other).size());
    }
}
