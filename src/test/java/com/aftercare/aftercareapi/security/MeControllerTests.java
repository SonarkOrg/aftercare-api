package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
}
