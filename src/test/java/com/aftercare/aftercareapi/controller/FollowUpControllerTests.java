package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.aftercare.aftercareapi.Repositories.PatientAccessLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

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
        assertEquals("2", f.get("questionnaireVersion").asText());
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

    // Each range test uses its own date window: the test database is shared across the context.

    /** Enrols a new patient with a follow-up discharged three days before {@code today}. The clock is set to {@code today}. */
    private JsonNode enrol(LocalDate today) throws Exception {
        clock.set(noon(today));
        return newFollowUp(newPatient(), "VISCERAL_SURGERY", today.minusDays(3).toString(), null);
    }

    private void checkInOn(JsonNode enrolment, LocalDate date, int pain) throws Exception {
        clock.set(noon(date));
        mockMvc.perform(post("/api/public/q/" + enrolment.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(pain, 36.8, false))).andExpect(status().isCreated());
    }

    private static Instant noon(LocalDate d) {
        return Instant.parse(d + "T16:00:00Z"); // 12:00 America/Toronto
    }

    private static long patientOf(JsonNode enrolment) {
        return enrolment.get("followUp").get("patientId").asLong();
    }

    @Test
    void checkInRangeReturnsAllPatientsSorted() throws Exception {
        LocalDate d = LocalDate.of(2028, 6, 12);
        JsonNode first = enrol(d);
        JsonNode second = enrol(d);
        checkInOn(first, d, 5);                 // day D, submitted first
        checkInOn(second, d.minusDays(2), 4);   // day D-2
        checkInOn(first, d.minusDays(1), 3);    // day D-1
        clock.set(noon(d));
        MvcResult r = call(get("/api/check-ins?from=" + d.minusDays(2) + "&to=" + d));
        assertEquals(200, r.getResponse().getStatus());
        JsonNode list = json(r);
        assertEquals(3, list.size());
        assertEquals(patientOf(second), list.get(0).get("patientId").asLong());
        assertEquals(d.minusDays(2).toString(), list.get(0).get("checkInDate").asText());
        assertEquals(second.get("followUp").get("id").asLong(), list.get(0).get("followUpId").asLong());
        assertEquals(patientOf(first), list.get(1).get("patientId").asLong());
        assertEquals(d.minusDays(1).toString(), list.get(1).get("checkInDate").asText());
        assertEquals(patientOf(first), list.get(2).get("patientId").asLong());
        assertEquals(d.toString(), list.get(2).get("checkInDate").asText());
        for (JsonNode c : list) {
            assertTrue(c.get("response").isObject());
        }
        assertEquals(VISCERAL_Q, list.get(0).get("response").get("questionnaire").asText());
    }

    @Test
    void checkInRangeBreaksDateTiesByIdAsc() throws Exception {
        LocalDate d = LocalDate.of(2028, 9, 20);
        JsonNode a = enrol(d);
        JsonNode b = enrol(d);
        checkInOn(b, d, 2); // submitted first, so lower id
        checkInOn(a, d, 2);
        clock.set(noon(d));
        JsonNode list = json(call(get("/api/check-ins?from=" + d + "&to=" + d)));
        assertEquals(2, list.size());
        assertEquals(patientOf(b), list.get(0).get("patientId").asLong());
        assertEquals(patientOf(a), list.get(1).get("patientId").asLong());
    }

    @Test
    void checkInRangeDefaultsToToday() throws Exception {
        LocalDate d = LocalDate.of(2028, 7, 12);
        JsonNode p = enrol(d);
        checkInOn(p, d.minusDays(1), 2);
        checkInOn(p, d, 6);
        clock.set(noon(d));
        JsonNode list = json(call(get("/api/check-ins")));
        assertEquals(1, list.size());
        assertEquals(d.toString(), list.get(0).get("checkInDate").asText());
        assertEquals(patientOf(p), list.get(0).get("patientId").asLong());
    }

    @Test
    void checkInRangeBoundsInclusive() throws Exception {
        LocalDate d = LocalDate.of(2028, 8, 12);
        JsonNode p = enrol(d);
        checkInOn(p, d.minusDays(2), 1);
        checkInOn(p, d.minusDays(1), 2);
        checkInOn(p, d, 3);
        clock.set(noon(d));
        JsonNode list = json(call(get("/api/check-ins?from=" + d.minusDays(1) + "&to=" + d.minusDays(1))));
        assertEquals(1, list.size());
        assertEquals(d.minusDays(1).toString(), list.get(0).get("checkInDate").asText());
    }

    @Test
    void checkInRangeRejectsReversed() throws Exception {
        MvcResult r = call(get("/api/check-ins?from=2028-09-12&to=2028-09-11"));
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(r).get("error").asText());
        assertEquals("must not be before from", json(r).get("fieldErrors").get("to").asText());
    }

    @Test
    void checkInRangeRejects32DaysAndAccepts31() throws Exception {
        assertEquals(200, call(get("/api/check-ins?from=2028-10-01&to=2028-10-31")).getResponse().getStatus());
        MvcResult r = call(get("/api/check-ins?from=2028-10-01&to=2028-11-01"));
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("range must not exceed 31 days", json(r).get("fieldErrors").get("to").asText());
    }

    @Test
    void checkInRangeRejectsBadDate() throws Exception {
        MvcResult r = call(get("/api/check-ins?from=garbage"));
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("invalid value", json(r).get("fieldErrors").get("from").asText());
    }

    @Test
    void checkInRangeRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/check-ins?from=2028-10-01&to=2028-10-02")).andExpect(status().isUnauthorized());
    }

    @Test
    void patientCheckInsIncludePatientId() throws Exception {
        LocalDate d = LocalDate.of(2028, 5, 20);
        JsonNode p = enrol(d);
        long pid = patientOf(p);
        checkInOn(p, d.minusDays(1), 4);
        clock.set(noon(d));
        JsonNode list = json(call(get("/api/patients/" + pid + "/check-ins")));
        assertEquals(1, list.size());
        JsonNode c = list.get(0);
        assertEquals(pid, c.get("patientId").asLong());
        Set<String> names = new HashSet<>();
        c.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("id", "patientId", "followUpId", "checkInDate", "submittedAt", "questionnaireDefinitionId",
                "questionnaireUrl", "questionnaireVersion", "response"), names);
        assertEquals(d.minusDays(1).toString(), c.get("checkInDate").asText());
    }
}
