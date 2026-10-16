package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class ContactPreferencesTests extends IntegrationTest {

    private MvcResult putPrefs(long p, String channel, String language, String consent, com.aftercare.aftercareapi.Enums.AccessRole role) throws Exception {
        return as(asRole(role), put("/api/patients/" + p + "/contact-preferences").contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredChannel\":\"" + channel + "\",\"language\":" + (language == null ? "null" : "\"" + language + "\"")
                        + ",\"contactConsent\":\"" + consent + "\"}"));
    }

    @Test
    void defaults() throws Exception {
        long p = newPatient();
        JsonNode prefs = json(call(get("/api/patients/" + p + "/contact-preferences")));
        assertEquals(p, prefs.get("patientId").asLong());
        assertEquals("EMAIL", prefs.get("preferredChannel").asText());
        assertTrue(prefs.get("language").isNull());
        assertEquals("FR", prefs.get("effectiveLanguage").asText()); // the clinic default language
        assertEquals("UNKNOWN", prefs.get("contactConsent").asText());
        assertTrue(prefs.get("consentUpdatedAt").isNull());
        assertTrue(prefs.get("consentUpdatedByName").isNull());
        assertFalse(prefs.get("emailAvailable").asBoolean(), "channels are off by default");
        assertFalse(prefs.get("smsAvailable").asBoolean());
        assertEquals(404, call(get("/api/patients/987654321/contact-preferences")).getResponse().getStatus());
    }

    @Test
    void anUpdateRecordsWhoAndWhenAndAuditsFieldNamesOnly() throws Exception {
        long p = newPatient();
        MvcResult r = putPrefs(p, "SMS", "EN", "GRANTED", AccessRole.NURSE);
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode prefs = json(r);
        assertEquals("SMS", prefs.get("preferredChannel").asText());
        assertEquals("EN", prefs.get("language").asText());
        assertEquals("EN", prefs.get("effectiveLanguage").asText());
        assertEquals("GRANTED", prefs.get("contactConsent").asText());
        assertFalse(prefs.get("consentUpdatedAt").isNull());
        assertEquals("Test Nurse", prefs.get("consentUpdatedByName").asText());
        Map<String, Object> audit = jdbc.queryForMap("select detail, resource_type, resource_id, actor_sub from audit_events where patient_id = ? and action = 'CONTACT_PREFERENCES_UPDATED'", p);
        assertEquals("contactConsent,language,preferredChannel", audit.get("detail"));
        assertEquals("PATIENT", audit.get("resource_type"));
        assertEquals(SUB_NURSE, audit.get("actor_sub"));

        // an identical update changes nothing and is not audited
        clock.plus(java.time.Duration.ofMinutes(5));
        putPrefs(p, "SMS", "EN", "GRANTED", AccessRole.RECEPTION);
        assertEquals(1, auditCount(p, "CONTACT_PREFERENCES_UPDATED"));
        assertEquals("Test Nurse", json(call(get("/api/patients/" + p + "/contact-preferences"))).get("consentUpdatedByName").asText());

        // changing only the channel keeps the consent stamp and audits just that field
        putPrefs(p, "EMAIL", "EN", "GRANTED", AccessRole.RECEPTION);
        assertEquals("preferredChannel", jdbc.queryForObject("select detail from audit_events where patient_id = ? and action = 'CONTACT_PREFERENCES_UPDATED' order by id desc limit 1", String.class, p));
        assertEquals("Test Nurse", json(call(get("/api/patients/" + p + "/contact-preferences"))).get("consentUpdatedByName").asText());

        // withdrawing is a consent change by whoever does it
        putPrefs(p, "EMAIL", "EN", "WITHDRAWN", AccessRole.RECEPTION);
        JsonNode after = json(call(get("/api/patients/" + p + "/contact-preferences")));
        assertEquals("WITHDRAWN", after.get("contactConsent").asText());
        assertEquals("Test Reception", after.get("consentUpdatedByName").asText());
        // and it can be granted again
        assertEquals(200, putPrefs(p, "EMAIL", "EN", "GRANTED", AccessRole.NURSE).getResponse().getStatus());
    }

    @Test
    void consentCannotGoBackToUnknown() throws Exception {
        long p = newPatient();
        assertEquals(200, putPrefs(p, "EMAIL", null, "UNKNOWN", AccessRole.NURSE).getResponse().getStatus(), "UNKNOWN to UNKNOWN is allowed");
        putPrefs(p, "EMAIL", null, "GRANTED", AccessRole.NURSE);
        MvcResult r = putPrefs(p, "EMAIL", null, "UNKNOWN", AccessRole.NURSE);
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("cannot be reset to UNKNOWN", json(r).get("fieldErrors").get("contactConsent").asText());
        putPrefs(p, "EMAIL", null, "WITHDRAWN", AccessRole.NURSE);
        assertEquals(400, putPrefs(p, "EMAIL", null, "UNKNOWN", AccessRole.NURSE).getResponse().getStatus());
    }

    @Test
    void requiredFieldsAreValidated() throws Exception {
        long p = newPatient();
        MvcResult r = call(put("/api/patients/" + p + "/contact-preferences").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(json(r).get("fieldErrors").has("preferredChannel"));
        assertTrue(json(r).get("fieldErrors").has("contactConsent"));
        assertEquals(404, putPrefs(987654321L, "EMAIL", null, "GRANTED", AccessRole.NURSE).getResponse().getStatus());
    }

    @Test
    void availabilityFollowsTheSettingsAndTheValidityOfTheAddress() throws Exception {
        long p = newPatient(); // email t@example.com, phone 514-555-0100
        settings(true, false);
        JsonNode a = json(call(get("/api/patients/" + p + "/contact-preferences")));
        assertTrue(a.get("emailAvailable").asBoolean());
        assertFalse(a.get("smsAvailable").asBoolean(), "SMS is off in settings");
        settings(true, true);
        assertTrue(json(call(get("/api/patients/" + p + "/contact-preferences"))).get("smsAvailable").asBoolean());
        settings(false, true);
        a = json(call(get("/api/patients/" + p + "/contact-preferences")));
        assertFalse(a.get("emailAvailable").asBoolean());
        assertTrue(a.get("smsAvailable").asBoolean());

        // an unusable phone number
        settings(true, true);
        String body = "{\"firstName\":\"Test\",\"lastName\":\"Patient\",\"email\":\"t@example.com\",\"phone\":\"555\",\"gender\":\"female\","
                + "\"address\":{\"city\":\"Montreal\",\"state\":\"QC\",\"zip\":\"H2X 1Y4\"}}";
        assertEquals(200, call(put("/api/patients/" + p).contentType(MediaType.APPLICATION_JSON).content(body)).getResponse().getStatus());
        a = json(call(get("/api/patients/" + p + "/contact-preferences")));
        assertFalse(a.get("smsAvailable").asBoolean());
        assertTrue(a.get("emailAvailable").asBoolean());
    }

    @Test
    void receptionCanReadAndWrite() throws Exception {
        long p = newPatient();
        assertEquals(200, as(asRole(AccessRole.RECEPTION), get("/api/patients/" + p + "/contact-preferences")).getResponse().getStatus());
        assertEquals(200, putPrefs(p, "EMAIL", "FR", "GRANTED", AccessRole.RECEPTION).getResponse().getStatus());
    }

    @Test
    void theClinicDefaultLanguageIsTheEffectiveLanguage() throws Exception {
        long p = newPatient();
        jdbc.update("update clinic_settings set default_language = 'EN'");
        assertEquals("EN", json(call(get("/api/patients/" + p + "/contact-preferences"))).get("effectiveLanguage").asText());
        putPrefs(p, "EMAIL", "FR", "UNKNOWN", AccessRole.NURSE);
        assertEquals("FR", json(call(get("/api/patients/" + p + "/contact-preferences"))).get("effectiveLanguage").asText());
    }

    @Test
    void changingEmailOrPhoneResetsConsentAndCancelsQueuedMessagesOfThatChannel() throws Exception {
        settings(true, true);
        long p = newPatient(); // t@example.com, 514-555-0100
        consent(p, "GRANTED", "EMAIL", "EN");
        long fu = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", 7).get("followUp").get("id").asLong();
        long emailRow = insertOutbox(p, "FOLLOW_UP_LINK", "PENDING", true, clock.instant(), fu, null, "k-email-" + uniq());
        long smsRow = insertOutbox(p, "FOLLOW_UP_LINK", "PENDING", true, clock.instant(), fu, null, "k-sms-" + uniq());
        jdbc.update("update notification_outbox set channel = 'SMS' where id = ?", smsRow);
        long auditBefore = auditCount(p, "CONTACT_PREFERENCES_UPDATED");

        String body = "{\"firstName\":\"Test\",\"lastName\":\"Patient\",\"email\":\"new@example.com\",\"phone\":\"514-555-0100\","
                + "\"gender\":\"female\",\"address\":{\"city\":\"Montreal\",\"state\":\"QC\",\"zip\":\"H2X 1Y4\"}}";
        assertEquals(200, as(asRole(AccessRole.RECEPTION), put("/api/patients/" + p).contentType(MediaType.APPLICATION_JSON).content(body))
                .getResponse().getStatus());

        JsonNode prefs = json(call(get("/api/patients/" + p + "/contact-preferences")));
        assertEquals("UNKNOWN", prefs.get("contactConsent").asText());
        assertEquals(auditBefore + 1, auditCount(p, "CONTACT_PREFERENCES_UPDATED"));
        var audit = jdbc.queryForMap("select actor_sub, detail from audit_events where patient_id = ? and action = 'CONTACT_PREFERENCES_UPDATED' order by id desc limit 1", p);
        assertEquals("system:contact-change", audit.get("actor_sub"));
        assertEquals("contactConsent", audit.get("detail"));
        assertEquals("CANCELLED", jdbc.queryForObject("select status from notification_outbox where id = ?", String.class, emailRow));
        assertEquals("PENDING", jdbc.queryForObject("select status from notification_outbox where id = ?", String.class, smsRow),
                "the SMS row belongs to the unchanged channel");

        // consent is not touched by an unrelated change, nor when it was not granted
        consent(p, "GRANTED", "EMAIL", "EN");
        assertEquals(200, as(asRole(AccessRole.RECEPTION), put("/api/patients/" + p).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("Test\"", "Renamed\""))).getResponse().getStatus());
        assertEquals("GRANTED", json(call(get("/api/patients/" + p + "/contact-preferences"))).get("contactConsent").asText());
        // a changed phone cancels the queued SMS
        assertEquals(200, as(asRole(AccessRole.RECEPTION), put("/api/patients/" + p).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("514-555-0100", "514-555-0199"))).getResponse().getStatus());
        assertEquals("UNKNOWN", json(call(get("/api/patients/" + p + "/contact-preferences"))).get("contactConsent").asText());
        assertEquals("CANCELLED", jdbc.queryForObject("select status from notification_outbox where id = ?", String.class, smsRow));
    }

}
