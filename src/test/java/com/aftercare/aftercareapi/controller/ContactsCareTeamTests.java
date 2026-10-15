package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ContactsCareTeamTests extends IntegrationTest {

    private MvcResult postJ(String url, String body) throws Exception {
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void contactCrudAndValidation() throws Exception {
        long p = newPatient();
        String base = "/api/patients/" + p + "/emergency-contacts";
        MvcResult bad = postJ(base, "{\"name\":\"Luc\",\"relationship\":\"Spouse\",\"phone\":\"abc\",\"preferredLanguage\":\"FR\"}");
        assertEquals(400, bad.getResponse().getStatus());
        assertTrue(json(bad).get("fieldErrors").has("phone"));
        assertEquals(400, postJ(base, "{\"name\":\"Luc\",\"relationship\":\"Spouse\",\"phone\":\"12-34\",\"preferredLanguage\":\"FR\"}").getResponse().getStatus());
        MvcResult ok = postJ(base, "{\"name\":\"Luc\",\"relationship\":\"Spouse\",\"phone\":\"514-555-0144\",\"preferredLanguage\":\"FR\"}");
        assertEquals(201, ok.getResponse().getStatus());
        long id = json(ok).get("id").asLong();
        MvcResult up = call(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Luc T\",\"relationship\":\"Spouse\",\"phone\":\"514-555-0145\",\"preferredLanguage\":\"EN\"}"));
        assertEquals("EN", json(up).get("preferredLanguage").asText());
        assertEquals(1, json(call(get(base))).size());
        long other = newPatient();
        assertEquals(404, call(delete("/api/patients/" + other + "/emergency-contacts/" + id)).getResponse().getStatus());
        assertEquals(204, call(delete(base + "/" + id)).getResponse().getStatus());
        assertEquals(0, json(call(get(base))).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from emergency_contacts where id = ?", Long.class, id));
    }

    @Test
    void careTeamAssignmentRules() throws Exception {
        long p = newPatient();
        long staff = newStaff("Nurse " + uniq(), "NURSE", null, null);
        String base = "/api/patients/" + p + "/care-team";
        MvcResult a = postJ(base, "{\"staffId\":" + staff + ",\"role\":null}");
        assertEquals(201, a.getResponse().getStatus());
        assertEquals("NURSE", json(a).get("role").asText());
        long assignmentId = json(a).get("assignmentId").asLong();

        MvcResult dup = postJ(base, "{\"staffId\":" + staff + ",\"role\":\"OTHER\"}");
        assertEquals(409, dup.getResponse().getStatus());
        assertEquals("ALREADY_ASSIGNED", json(dup).get("error").asText());

        long inactive = newStaff("Gone " + uniq(), "PHYSICIAN", null, null);
        call(post("/api/staff/" + inactive + "/deactivate"));
        MvcResult in = postJ(base, "{\"staffId\":" + inactive + ",\"role\":null}");
        assertEquals(409, in.getResponse().getStatus());
        assertEquals("STAFF_INACTIVE", json(in).get("error").asText());
        assertEquals(404, postJ(base, "{\"staffId\":987654,\"role\":null}").getResponse().getStatus());

        // deactivating an assigned member keeps the assignment, flagged inactive
        call(post("/api/staff/" + staff + "/deactivate"));
        JsonNode list = json(call(get(base)));
        assertEquals(1, list.size());
        assertFalse(list.get(0).get("active").asBoolean());

        assertEquals(404, call(delete("/api/patients/" + newPatient() + "/care-team/" + assignmentId)).getResponse().getStatus());
        assertEquals(204, call(delete(base + "/" + assignmentId)).getResponse().getStatus());
        assertEquals(0, json(call(get(base))).size());
        assertEquals(1, auditCount(p, "CARE_TEAM_UNASSIGNED"));
    }
}
