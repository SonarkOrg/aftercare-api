package com.aftercare.aftercareapi.retention;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class SettingsNotificationsRetentionTests extends IntegrationTest {

    private static final String NOTIF = """
            {"emailEnabled":true,"smsEnabled":true,"checkInReminderEnabled":true,"checkInReminderTime":"17:30",
             "appointmentReminderEnabled":true,"appointmentReminderHours":72,"missedCheckInEnabled":false,"missedCheckInCutoff":"21:15"}""";

    private MvcResult putNotifications(String body) throws Exception {
        return as(asRole(AccessRole.ADMIN), put("/api/settings/notifications").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult putRetention(String body) throws Exception {
        return as(asRole(AccessRole.ADMIN), put("/api/settings/retention").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void notificationDefaults() throws Exception {
        JsonNode s = json(as(asRole(AccessRole.ADMIN), get("/api/settings/notifications")));
        assertFalse(s.get("emailEnabled").asBoolean());
        assertFalse(s.get("smsEnabled").asBoolean());
        assertEquals("LOG", s.get("emailProvider").asText());
        assertEquals("LOG", s.get("smsProvider").asText());
        assertFalse(s.get("checkInReminderEnabled").asBoolean());
        assertEquals("18:00", s.get("checkInReminderTime").asText());
        assertFalse(s.get("appointmentReminderEnabled").asBoolean());
        assertEquals(48, s.get("appointmentReminderHours").asInt());
        assertTrue(s.get("missedCheckInEnabled").asBoolean());
        assertEquals("20:00", s.get("missedCheckInCutoff").asText());
        assertTrue(s.get("updatedAt").isNull());
        assertTrue(s.get("updatedByName").isNull());
    }

    @Test
    void notificationSettingsRoundTripAndAreAudited() throws Exception {
        MvcResult r = putNotifications(NOTIF);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode s = json(r);
        assertTrue(s.get("emailEnabled").asBoolean());
        assertTrue(s.get("smsEnabled").asBoolean());
        assertEquals("17:30", s.get("checkInReminderTime").asText());
        assertEquals(72, s.get("appointmentReminderHours").asInt());
        assertFalse(s.get("missedCheckInEnabled").asBoolean());
        assertEquals("21:15", s.get("missedCheckInCutoff").asText());
        assertEquals("Test Admin", s.get("updatedByName").asText());
        assertFalse(s.get("updatedAt").isNull());
        assertEquals(s, json(as(asRole(AccessRole.ADMIN), get("/api/settings/notifications"))));

        var audit = jdbc.queryForMap("select resource_type, resource_id, patient_id, detail from audit_events where action = 'NOTIFICATION_SETTINGS_UPDATED' order by id desc limit 1");
        assertEquals("CLINIC_SETTINGS", audit.get("resource_type"));
        assertNull(audit.get("patient_id"));
        assertEquals("emailEnabled,smsEnabled,checkInReminderEnabled,checkInReminderTime,appointmentReminderEnabled,"
                + "appointmentReminderHours,missedCheckInEnabled,missedCheckInCutoff", audit.get("detail"));
        // an identical save changes nothing
        putNotifications(NOTIF);
        assertNull(jdbc.queryForList("select detail from audit_events where action = 'NOTIFICATION_SETTINGS_UPDATED' order by id desc limit 1", String.class).get(0));
    }

    @Test
    void notificationValidation() throws Exception {
        MvcResult order = putNotifications(NOTIF.replace("17:30", "21:15"));
        assertEquals(400, order.getResponse().getStatus());
        assertEquals("must be before the missed check-in cutoff", json(order).get("fieldErrors").get("checkInReminderTime").asText());
        assertEquals(400, putNotifications(NOTIF.replace("17:30", "21:16")).getResponse().getStatus());
        for (String hours : new String[]{"23", "73", "0"}) {
            MvcResult r = putNotifications(NOTIF.replace("72", hours));
            assertEquals(400, r.getResponse().getStatus(), hours);
            assertTrue(json(r).get("fieldErrors").has("appointmentReminderHours"));
        }
        assertEquals(200, putNotifications(NOTIF.replace("72", "24")).getResponse().getStatus());
        assertEquals(200, putNotifications(NOTIF.replace("72", "72")).getResponse().getStatus());
        for (String bad : new String[]{"25:00", "9:00", "07:60", "noon", ""}) {
            MvcResult r = putNotifications(NOTIF.replace("17:30", bad));
            assertEquals(400, r.getResponse().getStatus(), bad);
            assertTrue(json(r).get("fieldErrors").has("checkInReminderTime"), bad);
        }
        assertEquals(200, putNotifications(NOTIF.replace("17:30", "00:00").replace("21:15", "23:59")).getResponse().getStatus());
        // every field is required
        MvcResult missing = putNotifications("{\"emailEnabled\":true}");
        assertEquals(400, missing.getResponse().getStatus());
        assertTrue(json(missing).get("fieldErrors").has("smsEnabled"));
        assertTrue(json(missing).get("fieldErrors").has("missedCheckInCutoff"));
    }

    @Test
    void providersAreReadOnlyAndComeFromConfiguration() throws Exception {
        JsonNode s = json(putNotifications(NOTIF.replace("{", "{\"emailProvider\":\"SMTP\",\"smsProvider\":\"TWILIO\",\"updatedByName\":\"Mallory\",")));
        assertEquals("LOG", s.get("emailProvider").asText());
        assertEquals("LOG", s.get("smsProvider").asText());
        assertEquals("Test Admin", s.get("updatedByName").asText());
    }

    @Test
    void retentionDefaultsRoundTripAndAudit() throws Exception {
        JsonNode d = json(as(asRole(AccessRole.ADMIN), get("/api/settings/retention")));
        for (String f : new String[]{"closedFollowUpMonths", "deletedDocumentMonths", "messageMonths", "notificationMonths", "auditMonths"}) {
            assertTrue(d.has(f) && d.get(f).isNull(), f);
        }
        assertFalse(d.get("purgeEnabled").asBoolean());
        assertTrue(d.get("dryRun").asBoolean());
        assertTrue(d.get("updatedAt").isNull());
        assertTrue(d.get("updatedByName").isNull());

        MvcResult r = putRetention("{\"closedFollowUpMonths\":null,\"deletedDocumentMonths\":12,\"messageMonths\":84,\"notificationMonths\":null,\"auditMonths\":1200}");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode s = json(r);
        assertEquals(84, s.get("messageMonths").asInt());
        assertEquals(12, s.get("deletedDocumentMonths").asInt());
        assertEquals(1200, s.get("auditMonths").asInt());
        assertTrue(s.get("closedFollowUpMonths").isNull());
        assertEquals("Test Admin", s.get("updatedByName").asText());
        assertFalse(s.get("updatedAt").isNull());
        assertEquals(s, json(as(asRole(AccessRole.ADMIN), get("/api/settings/retention"))));

        var audit = jdbc.queryForMap("select resource_type, patient_id, detail from audit_events where action = 'RETENTION_SETTINGS_UPDATED' order by id desc limit 1");
        assertEquals("RETENTION", audit.get("resource_type"));
        assertNull(audit.get("patient_id"));
        assertEquals("deletedDocumentMonths=null->12,messageMonths=null->84,auditMonths=null->1200", audit.get("detail"));
        putRetention("{\"messageMonths\":null,\"deletedDocumentMonths\":12,\"auditMonths\":1200}");
        assertEquals("messageMonths=84->null", jdbc.queryForList("select detail from audit_events where action = 'RETENTION_SETTINGS_UPDATED' order by id desc limit 1", String.class).get(0));
    }

    @Test
    void retentionValidation() throws Exception {
        for (String bad : new String[]{"0", "-5", "1201"}) {
            MvcResult r = putRetention("{\"messageMonths\":" + bad + "}");
            assertEquals(400, r.getResponse().getStatus(), bad);
            assertEquals("must be between 1 and 1200 months", json(r).get("fieldErrors").get("messageMonths").asText());
        }
        MvcResult many = putRetention("{\"closedFollowUpMonths\":0,\"auditMonths\":2000}");
        assertTrue(json(many).get("fieldErrors").has("closedFollowUpMonths"));
        assertTrue(json(many).get("fieldErrors").has("auditMonths"));
        assertEquals(200, putRetention("{\"messageMonths\":1}").getResponse().getStatus());
        assertEquals(200, putRetention("{}").getResponse().getStatus());
        assertEquals(400, putRetention("{\"messageMonths\":\"soon\"}").getResponse().getStatus());
    }

    @Test
    void savingTheClinicProfileLeavesTheNewColumnsAlone() throws Exception {
        assertEquals(200, putNotifications(NOTIF).getResponse().getStatus());
        assertEquals(200, putRetention("{\"messageMonths\":84,\"auditMonths\":120}").getResponse().getStatus());
        String clinic = "{\"name\":\"Clinique Test\",\"addressLine\":null,\"city\":\"Montréal\",\"province\":\"QC\",\"postalCode\":\"H2X 2T1\","
                + "\"phone\":\"514-555-0199\",\"defaultLanguage\":\"EN\",\"notifySeverities\":[\"HIGH\"]}";
        MvcResult r = as(asRole(AccessRole.ADMIN), put("/api/settings/clinic").contentType(MediaType.APPLICATION_JSON).content(clinic));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode n = json(as(asRole(AccessRole.ADMIN), get("/api/settings/notifications")));
        assertTrue(n.get("emailEnabled").asBoolean());
        assertEquals("17:30", n.get("checkInReminderTime").asText());
        assertEquals("21:15", n.get("missedCheckInCutoff").asText());
        assertEquals(72, n.get("appointmentReminderHours").asInt());
        assertEquals("Test Admin", n.get("updatedByName").asText());
        JsonNode ret = json(as(asRole(AccessRole.ADMIN), get("/api/settings/retention")));
        assertEquals(84, ret.get("messageMonths").asInt());
        assertEquals(120, ret.get("auditMonths").asInt());
        // the clinic DTO has not grown
        JsonNode c = json(as(asRole(AccessRole.NURSE), get("/api/settings/clinic")));
        assertEquals(java.util.List.of("name", "addressLine", "city", "province", "postalCode", "phone", "defaultLanguage",
                "notifySeverities", "updatedAt", "updatedByName"), fieldNames(c));
    }

    private static java.util.List<String> fieldNames(JsonNode n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void onlyAdminsReadOrWriteThem() throws Exception {
        for (AccessRole role : new AccessRole[]{AccessRole.PHYSICIAN, AccessRole.NURSE, AccessRole.RECEPTION}) {
            assertEquals(403, as(asRole(role), get("/api/settings/notifications")).getResponse().getStatus());
            assertEquals(403, as(asRole(role), get("/api/settings/retention")).getResponse().getStatus());
        }
    }

    @Test
    void auditRetentionHasATwelveMonthMinimum() throws Exception {
        MvcResult low = putRetention("{\"auditMonths\":11}");
        assertEquals(400, low.getResponse().getStatus());
        assertEquals("must be at least 12 months", json(low).get("fieldErrors").get("auditMonths").asText());
        assertEquals(400, putRetention("{\"auditMonths\":1}").getResponse().getStatus());
        assertEquals(200, putRetention("{\"auditMonths\":12}").getResponse().getStatus());
        assertEquals(200, putRetention("{\"auditMonths\":null}").getResponse().getStatus());
        // other periods are not subject to it
        assertEquals(200, putRetention("{\"messageMonths\":1}").getResponse().getStatus());
    }

}
