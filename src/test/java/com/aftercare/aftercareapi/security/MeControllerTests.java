package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class MeControllerTests extends IntegrationTest {

    private static final List<String> ALL = List.of("PATIENT_READ", "PATIENT_WRITE", "PATIENT_DELETE", "CLINICAL_READ",
            "CLINICAL_WRITE", "HEALTH_NUMBER_REVEAL", "APPOINTMENT_READ", "APPOINTMENT_WRITE", "FORMS_READ", "REPORTS_READ",
            "AUDIT_READ", "STAFF_READ", "STAFF_MANAGE", "SETTINGS_READ", "SETTINGS_MANAGE");
    private static final List<String> CLINICAL = List.of("PATIENT_READ", "PATIENT_WRITE", "CLINICAL_READ", "CLINICAL_WRITE",
            "HEALTH_NUMBER_REVEAL", "APPOINTMENT_READ", "APPOINTMENT_WRITE", "FORMS_READ", "REPORTS_READ", "STAFF_READ",
            "SETTINGS_READ");
    private static final List<String> RECEPTION = List.of("PATIENT_READ", "PATIENT_WRITE", "APPOINTMENT_READ",
            "APPOINTMENT_WRITE", "FORMS_READ", "STAFF_READ", "SETTINGS_READ");

    private static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    @Test
    void eachRoleGetsItsExactPermissionsInContractOrder() throws Exception {
        for (AccessRole role : AccessRole.values()) {
            MvcResult r = as(asRole(role), get("/api/me"));
            assertEquals(200, r.getResponse().getStatus(), role.name());
            JsonNode me = json(r);
            assertEquals(role.name(), me.get("accessRole").asText());
            assertTrue(me.get("staffId").asLong() > 0);
            assertFalse(me.get("displayName").asText().isBlank());
            assertTrue(me.has("email") && me.has("role") && me.has("sub"));
            List<String> expected = switch (role) {
                case ADMIN -> ALL;
                case PHYSICIAN, NURSE -> CLINICAL;
                case RECEPTION -> RECEPTION;
            };
            assertEquals(expected, list(me.get("permissions")), role.name());
        }
    }

    @Test
    void meOfTheSeededAdminCarriesProfessionAndSub() throws Exception {
        JsonNode me = json(as(asRole(AccessRole.ADMIN), get("/api/me")));
        assertEquals("test|admin", me.get("sub").asText());
        assertEquals("Test Admin", me.get("displayName").asText());
        assertEquals("OTHER", me.get("role").asText());
        assertTrue(me.get("email").isNull());
    }

    @Test
    void unlinkedAndInactiveGetNotStaff() throws Exception {
        for (var who : List.of(UNLINKED, staffAs(SUB_INACTIVE))) {
            MvcResult r = as(who, get("/api/me"));
            assertEquals(403, r.getResponse().getStatus());
            assertEquals("NOT_STAFF", json(r).get("error").asText());
            assertEquals("Your login is not linked to an active staff member", json(r).get("message").asText());
        }
    }

    @Test
    void noTokenIs401() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/me")).andReturn().getResponse().getStatus());
    }

    @Test
    void deactivationLocksOutOnTheNextRequest() throws Exception {
        String u = uniq();
        long id = newStaff("Short Stay " + u, "NURSE", "NURSE", null, "auth0|short-" + u);
        var who = staffAs("auth0|short-" + u);
        assertEquals(200, as(who, get("/api/me")).getResponse().getStatus());
        assertEquals(200, as(who, get("/api/patients")).getResponse().getStatus());
        assertEquals(200, call(post("/api/staff/" + id + "/deactivate")).getResponse().getStatus());
        assertEquals(403, as(who, get("/api/me")).getResponse().getStatus());
        MvcResult r = as(who, get("/api/patients"));
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("NOT_STAFF", json(r).get("error").asText());
        assertEquals(200, call(post("/api/staff/" + id + "/reactivate")).getResponse().getStatus());
        assertEquals(200, as(who, get("/api/me")).getResponse().getStatus());
    }

    private MvcResult prefs(org.springframework.test.web.servlet.request.RequestPostProcessor who, String body) throws Exception {
        return as(who, put("/api/me/preferences").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void uiLanguageIsNullUntilChosenThenFollowsThePreference() throws Exception {
        String u = uniq();
        newStaff("Lang " + u, "NURSE", "NURSE", null, "auth0|lang-" + u);
        var who = staffAs("auth0|lang-" + u);
        JsonNode before = json(as(who, get("/api/me")));
        assertTrue(before.has("uiLanguage"));
        assertTrue(before.get("uiLanguage").isNull());
        MvcResult r = prefs(who, "{\"uiLanguage\":\"EN\"}");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("EN", json(r).get("uiLanguage").asText());
        assertEquals("NURSE", json(r).get("accessRole").asText());
        assertEquals("EN", json(as(who, get("/api/me"))).get("uiLanguage").asText());
        assertEquals("FR", json(prefs(who, "{\"uiLanguage\":\"FR\"}")).get("uiLanguage").asText());
        assertEquals("FR", json(as(who, get("/api/me"))).get("uiLanguage").asText());
    }

    @Test
    void invalidPreferencesAreRejected() throws Exception {
        MvcResult none = prefs(asRole(AccessRole.NURSE), "{\"uiLanguage\":null}");
        assertEquals(400, none.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(none).get("error").asText());
        assertEquals("must not be null", json(none).get("fieldErrors").get("uiLanguage").asText());
        assertEquals(400, prefs(asRole(AccessRole.NURSE), "{}").getResponse().getStatus());
        MvcResult de = prefs(asRole(AccessRole.NURSE), "{\"uiLanguage\":\"DE\"}");
        assertEquals(400, de.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(de).get("error").asText());
        assertEquals("FR", json(prefs(asRole(AccessRole.NURSE), "{\"uiLanguage\":\"FR\"}")).get("uiLanguage").asText());
    }

    @Test
    void unlinkedLoginIsNotStaff() throws Exception {
        MvcResult r = prefs(UNLINKED, "{\"uiLanguage\":\"EN\"}");
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("NOT_STAFF", json(r).get("error").asText());
    }

    @Test
    void receptionCanSetItsOwnPreferenceAndNobodyElses() throws Exception {
        Integer othersBefore = jdbc.queryForObject("select count(*) from staff_members where ui_language = 'EN' and auth0subject <> ?", Integer.class, SUB_RECEPTION);
        MvcResult r = prefs(asRole(AccessRole.RECEPTION), "{\"uiLanguage\":\"EN\",\"staffId\":1,\"id\":1,\"sub\":\"test|admin\"}");
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("RECEPTION", json(r).get("accessRole").asText());
        assertEquals("EN", jdbc.queryForObject("select ui_language from staff_members where auth0subject = ?", String.class, SUB_RECEPTION));
        assertEquals(othersBefore, jdbc.queryForObject("select count(*) from staff_members where ui_language = 'EN' and auth0subject <> ?", Integer.class, SUB_RECEPTION));
        jdbc.update("update staff_members set ui_language = null where auth0subject = ?", SUB_RECEPTION);
    }

    @Test
    void bean_validation_messages_stay_english_whatever_the_accept_language() throws Exception {
        MvcResult r = as(asRole(AccessRole.NURSE), post("/api/patients/register").contentType(MediaType.APPLICATION_JSON)
                .header("Accept-Language", "fr-CA,fr;q=0.9").content("{}"));
        assertEquals(400, r.getResponse().getStatus());
        JsonNode fields = json(r).get("fieldErrors");
        assertTrue(fields.size() > 0);
        fields.forEach(m -> assertTrue(m.asText().matches("[A-Za-z0-9 ,.()'-]+") && !m.asText().contains("doit") && !m.asText().contains("ne peut"), m.asText()));
        assertEquals("must not be blank", fields.get("firstName").asText());
    }

    @Test
    void databaseRejectsAnUnknownLanguage() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update staff_members set ui_language = 'DE' where auth0subject = ?", SUB_RECEPTION));
    }
}
