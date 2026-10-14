package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.aftercare.aftercareapi.Repositories.PatientAccessLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FollowUpControllerTests extends IntegrationTest {

    private MvcResult create(long pid, String body) throws Exception {
        return call(post("/api/patients/" + pid + "/follow-ups").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int publicGet(String code) throws Exception {
        return mockMvc.perform(get("/api/public/q/" + code)).andReturn().getResponse().getStatus();
    }

    @Test
    void createReturnsCodeAndDefaults() throws Exception {
        long pid = newPatient();
        JsonNode r = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        assertEquals(43, r.get("accessCode").asText().length());
        JsonNode f = r.get("followUp");
        assertEquals("auth0|nurse-a", f.get("createdBy").asText());
        assertEquals("2026-10-20", f.get("endDate").asText());
        assertEquals(7, f.get("durationDays").asInt());
        assertEquals("ACTIVE", f.get("status").asText());
        assertEquals(pid, f.get("patientId").asLong());
        assertEquals("https://aftercare.example/fhir/Questionnaire/followup-visceral-surgery", f.get("questionnaireUrl").asText());
        assertEquals("1", f.get("questionnaireVersion").asText());
        assertFalse(f.get("accessLinkIssuedAt").isNull());
        assertTrue(f.get("closedAt").isNull());
        assertEquals(200, publicGet(r.get("accessCode").asText()));
    }

    @Test
    void duplicateActiveGives409AndExpiredIsAutoClosed() throws Exception {
        long pid = newPatient();
        newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        MvcResult dup = create(pid, "{\"specialty\":\"VISCERAL_SURGERY\",\"dischargeDate\":\"2026-10-13\"}");
        assertEquals(409, dup.getResponse().getStatus());
        assertEquals("FOLLOW_UP_ALREADY_ACTIVE", json(dup).get("error").asText());

        long pid2 = newPatient();
        newFollowUp(pid2, "VISCERAL_SURGERY", "2026-10-01", 3); // ended 10-04, expired on 10-14
        clock.plus(java.time.Duration.ofMinutes(1));
        newFollowUp(pid2, "ORTHOPEDIC_SURGERY", "2026-10-13", null);
        JsonNode list = json(call(get("/api/patients/" + pid2 + "/follow-ups")));
        assertEquals(2, list.size());
        assertEquals("ACTIVE", list.get(0).get("status").asText());
        assertEquals("ORTHOPEDIC_SURGERY", list.get(0).get("specialty").asText());
        assertEquals("CLOSED", list.get(1).get("status").asText());
        assertEquals("auth0|nurse-a", list.get(1).get("closedBy").asText());
    }

    @Test
    void validation() throws Exception {
        long pid = newPatient();
        MvcResult future = create(pid, "{\"specialty\":\"VISCERAL_SURGERY\",\"dischargeDate\":\"2026-10-15\"}");
        assertEquals(400, future.getResponse().getStatus());
        assertEquals("must not be in the future", json(future).get("fieldErrors").get("dischargeDate").asText());
        MvcResult old = create(pid, "{\"specialty\":\"VISCERAL_SURGERY\",\"dischargeDate\":\"2026-09-13\"}");
        assertEquals(400, old.getResponse().getStatus());
        assertEquals("must be within the last 30 days", json(old).get("fieldErrors").get("dischargeDate").asText());
        for (String d : new String[]{"0", "31"}) {
            MvcResult r = create(pid, "{\"specialty\":\"VISCERAL_SURGERY\",\"dischargeDate\":\"2026-10-13\",\"durationDays\":" + d + "}");
            assertEquals(400, r.getResponse().getStatus());
            assertEquals("VALIDATION_FAILED", json(r).get("error").asText());
            assertTrue(json(r).get("fieldErrors").has("durationDays"));
        }
        MvcResult bad = create(pid, "{\"specialty\":\"CARDIOLOGY\",\"dischargeDate\":\"2026-10-13\"}");
        assertEquals(400, bad.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(bad).get("error").asText());
        assertEquals(30, newFollowUp(pid, "VISCERAL_SURGERY", "2026-09-14", 30).get("followUp").get("durationDays").asInt());
    }

    @Test
    void unknownPatientIs404InApiErrorShape() throws Exception {
        MvcResult r = create(987654, "{\"specialty\":\"VISCERAL_SURGERY\",\"dischargeDate\":\"2026-10-13\"}");
        assertEquals(404, r.getResponse().getStatus());
        JsonNode e = json(r);
        assertEquals(404, e.get("status").asInt());
        assertEquals("NOT_FOUND", e.get("error").asText());
        assertEquals("Patient not found", e.get("message").asText());
        assertTrue(e.get("fieldErrors").isObject());
        assertEquals(404, call(get("/api/patients/987654/follow-ups")).getResponse().getStatus());
        assertEquals(404, call(get("/api/patients/987654/check-ins")).getResponse().getStatus());
    }

    @Test
    void regenerateReplacesCode() throws Exception {
        long pid = newPatient();
        JsonNode r = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        String oldCode = r.get("accessCode").asText();
        long id = r.get("followUp").get("id").asLong();
        JsonNode regen = json(call(post("/api/follow-ups/" + id + "/access-code")));
        String newCode = regen.get("accessCode").asText();
        assertNotEquals(oldCode, newCode);
        assertEquals(404, publicGet(oldCode));
        assertEquals(200, publicGet(newCode));
    }

    @Autowired
    PatientAccessLinkRepository linkRepository;

    @Test
    void twoRegenerationsLeaveOnlyOneLiveLink() throws Exception {
        JsonNode r = newFollowUp(newPatient(), "VISCERAL_SURGERY", "2026-10-13", null);
        long id = r.get("followUp").get("id").asLong();
        call(post("/api/follow-ups/" + id + "/access-code"));
        String last = json(call(post("/api/follow-ups/" + id + "/access-code"))).get("accessCode").asText();
        var live = linkRepository.findByFollowUpIdAndRevokedAtIsNull(id);
        assertEquals(1, live.size());
        assertEquals(3, linkRepository.findAll().stream()
                .filter(l -> l.getFollowUp() != null && l.getFollowUp().getId() == id).count());
        assertEquals(200, publicGet(last));
        assertEquals(404, publicGet(r.get("accessCode").asText()));
    }

    @Test
    void closeMakesCodeUnusableAndBlocksFurtherChanges() throws Exception {
        long pid = newPatient();
        JsonNode r = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        long id = r.get("followUp").get("id").asLong();
        MvcResult close = call(post("/api/follow-ups/" + id + "/close"));
        assertEquals(200, close.getResponse().getStatus());
        assertEquals("CLOSED", json(close).get("status").asText());
        assertEquals(404, publicGet(r.get("accessCode").asText()));
        MvcResult again = call(post("/api/follow-ups/" + id + "/close"));
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("INVALID_STATE", json(again).get("error").asText());
        assertEquals(409, call(post("/api/follow-ups/" + id + "/access-code")).getResponse().getStatus());
        assertEquals(404, call(post("/api/follow-ups/999999/close")).getResponse().getStatus());
    }

    @Test
    void endpointsRequireJwt() throws Exception {
        mockMvc.perform(post("/api/patients/1/follow-ups").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/patients/1/follow-ups")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/follow-ups/1/access-code")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/follow-ups/1/close")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/patients/1/check-ins")).andExpect(status().isUnauthorized());
    }

    @Test
    void checkInsReturnResponseAsObject() throws Exception {
        long pid = newPatient();
        JsonNode r = newFollowUp(pid, "VISCERAL_SURGERY", "2026-10-13", null);
        mockMvc.perform(post("/api/public/q/" + r.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(2, 36.8, false))).andExpect(status().isCreated());
        JsonNode list = json(call(get("/api/patients/" + pid + "/check-ins")));
        assertEquals(1, list.size());
        JsonNode c = list.get(0);
        assertTrue(c.get("response").isObject());
        assertEquals(VISCERAL_Q, c.get("response").get("questionnaire").asText());
        assertEquals("2026-10-14", c.get("checkInDate").asText());
        assertEquals(r.get("followUp").get("id").asLong(), c.get("followUpId").asLong());
    }
}
