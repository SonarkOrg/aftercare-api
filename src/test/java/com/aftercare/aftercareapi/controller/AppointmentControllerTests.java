package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.Appointment;
import com.aftercare.aftercareapi.Repositories.AppointmentRepository;
import com.aftercare.aftercareapi.Repositories.PatientAccessLinkRepository;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppointmentControllerTests extends IntegrationTest {

    @Autowired
    AppointmentRepository appointmentRepository;
    @Autowired
    PatientAccessLinkRepository linkRepository;

    @Test
    void twoRegenerationsLeaveOnlyOneLiveLink() throws Exception {
        JsonNode a = newAppointment(newPatient(), "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2027-06-01T09:00:00");
        long id = id(a);
        assertEquals(200, call(post("/api/appointments/" + id + "/access-code")).getResponse().getStatus());
        assertEquals(200, call(post("/api/appointments/" + id + "/access-code")).getResponse().getStatus());
        assertEquals(1, linkRepository.findByAppointmentIdAndRevokedAtIsNull(id).size());
        assertEquals(3, linkRepository.findByAppointmentId(id).size());
    }

    @Test
    void scheduledAtInDstGapIsRejected() throws Exception {
        long pid = newPatient();
        MvcResult r = createRaw(body(pid, "GENERAL_CONSULT", "2027-03-14T02:30:00", 30));
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(r).get("error").asText());
        assertTrue(json(r).get("fieldErrors").has("scheduledAt"));
        JsonNode ok = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2027-03-14T03:30:00");
        MvcResult u = put(id(ok), "GENERAL_CONSULT", "2027-03-14T02:30:00");
        assertEquals(400, u.getResponse().getStatus());
        assertTrue(json(u).get("fieldErrors").has("scheduledAt"));
    }

    @Test
    void metricsRangeCappedAt366Days() throws Exception {
        assertEquals(200, call(get("/api/appointments/metrics?from=2027-01-01&to=2028-01-01")).getResponse().getStatus());
        MvcResult r = call(get("/api/appointments/metrics?from=2027-01-01&to=2028-01-02"));
        assertEquals(400, r.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(r).get("error").asText());
        assertTrue(json(r).get("fieldErrors").has("to"));
    }

    private MvcResult createRaw(String body) throws Exception {
        return call(post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(long pid, String type, String at, int dur) {
        return "{\"patientId\":%d,\"type\":\"%s\",\"specialty\":\"ORTHOPEDIC_SURGERY\",\"scheduledAt\":\"%s\",\"durationMinutes\":%d}".formatted(pid, type, at, dur);
    }

    private long id(JsonNode withCode) {
        return withCode.get("appointment").get("id").asLong();
    }

    private JsonNode detail(long id) throws Exception {
        return json(call(get("/api/appointments/" + id)));
    }

    private MvcResult setStatus(long id, String st) throws Exception {
        return call(post("/api/appointments/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + st + "\"}"));
    }

    private MvcResult put(long id, String type, String at) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/appointments/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\",\"specialty\":\"ORTHOPEDIC_SURGERY\",\"scheduledAt\":\"%s\",\"durationMinutes\":45,\"location\":\"Room 3\"}".formatted(type, at)));
    }

    private void intake(JsonNode created, boolean surgery, boolean anticoagulant) throws Exception {
        String url = surgery ? SURGERY_URL : CONSULT_URL;
        mockMvc.perform(post("/api/public/q/" + created.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(intakeBody(url, surgery, anticoagulant, anticoagulant && surgery))).andExpect(status().isCreated());
    }

    @Test
    void createRoundTripsLocalTimeAndStoresUtc() throws Exception {
        long pid = newPatient();
        JsonNode r = newAppointment(pid, "SURGERY", "ORTHOPEDIC_SURGERY", "2026-10-16T08:00:00");
        assertEquals(43, r.get("accessCode").asText().length());
        JsonNode a = r.get("appointment");
        assertEquals("2026-10-16T08:00:00", a.get("scheduledAt").asText());
        assertEquals("SCHEDULED", a.get("status").asText());
        assertEquals("auth0|nurse-a", a.get("createdBy").asText());
        assertEquals(Instant.parse("2026-10-16T12:00:00Z"), appointmentRepository.findById(a.get("id").asLong()).map(Appointment::getScheduledAt).orElseThrow());
        assertTrue(a.get("location").isNull());
        assertEquals(0, a.get("flagCount").asInt());
    }

    @Test
    void createValidation() throws Exception {
        long pid = newPatient();
        MvcResult past = createRaw(body(pid, "SURGERY", "2026-10-13T08:00:00", 30));
        assertEquals(400, past.getResponse().getStatus());
        assertEquals("must be in the future", json(past).get("fieldErrors").get("scheduledAt").asText());
        assertEquals(400, createRaw(body(pid, "SURGERY", "2026-10-16T08:00", 30)).getResponse().getStatus());
        assertEquals(400, createRaw(body(pid, "SURGERY", "2026-10-16T08:00:00", 4)).getResponse().getStatus());
        assertEquals(400, createRaw(body(pid, "SURGERY", "2026-10-16T08:00:00", 481)).getResponse().getStatus());
        MvcResult nf = createRaw(body(987654, "SURGERY", "2026-10-16T08:00:00", 30));
        assertEquals(404, nf.getResponse().getStatus());
        assertEquals("Patient not found", json(nf).get("message").asText());
    }

    @Test
    void listDefaultsRangeAndFilters() throws Exception {
        long pid = newPatient();
        JsonNode late = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-14T16:00:00");
        JsonNode early = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-14T13:00:00");
        JsonNode today = json(call(get("/api/appointments")));
        java.util.List<Long> ids = new java.util.ArrayList<>();
        today.forEach(n -> ids.add(n.get("id").asLong()));
        assertTrue(ids.indexOf(id(early)) >= 0 && ids.indexOf(id(early)) < ids.indexOf(id(late)));
        assertEquals(400, call(get("/api/appointments?from=2026-10-14&to=2026-10-13")).getResponse().getStatus());
        MvcResult tooLong = call(get("/api/appointments?from=2026-10-14&to=2026-11-14"));
        assertEquals(400, tooLong.getResponse().getStatus());
        assertTrue(json(tooLong).get("fieldErrors").has("to"));
        assertEquals(200, call(get("/api/appointments?from=2026-10-14&to=2026-11-13")).getResponse().getStatus());
        assertEquals(400, call(get("/api/appointments?from=garbage")).getResponse().getStatus());
        setStatus(id(late), "CANCELLED");
        JsonNode cancelled = json(call(get("/api/appointments?status=CANCELLED")));
        boolean found = false;
        for (JsonNode n : cancelled) {
            assertEquals("CANCELLED", n.get("status").asText());
            found |= n.get("id").asLong() == id(late);
        }
        assertTrue(found);
        assertEquals(1, json(call(get("/api/patients/" + pid + "/appointments"))).findValues("id").stream().filter(n -> n.asLong() == id(early)).count());
    }

    @Test
    void questionnaireMissingWindow() throws Exception {
        long pid = newPatient();
        Instant sched = Instant.parse("2026-12-01T17:00:00Z");
        JsonNode a = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-12-01T12:00:00");
        JsonNode withIntake = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-12-01T12:00:00");
        JsonNode cancelled = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-12-01T12:00:00");
        setStatus(id(cancelled), "CANCELLED");
        intake(withIntake, false, false);
        clock.set(sched.minus(Duration.ofHours(49)));
        assertFalse(detail(id(a)).get("questionnaireMissing").asBoolean());
        clock.set(sched.minus(Duration.ofHours(47)));
        assertTrue(detail(id(a)).get("questionnaireMissing").asBoolean());
        assertFalse(detail(id(withIntake)).get("questionnaireMissing").asBoolean());
        assertFalse(detail(id(cancelled)).get("questionnaireMissing").asBoolean());
    }

    @Test
    void metricsCountsAndRate() throws Exception {
        long pid = newPatient();
        String day = "2027-03-01";
        JsonNode a = newAppointment(pid, "PRE_OP_CONSULT", "ORTHOPEDIC_SURGERY", day + "T09:00:00");
        JsonNode b = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", day + "T10:00:00");
        JsonNode c = newAppointment(pid, "SURGERY", "ORTHOPEDIC_SURGERY", day + "T11:00:00");
        JsonNode d = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", day + "T12:00:00");
        JsonNode e = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", day + "T13:00:00");
        intake(a, false, false);
        intake(c, true, true);
        setStatus(id(d), "CANCELLED");
        setStatus(id(e), "COMPLETED");
        clock.set(Instant.parse("2027-03-01T10:00:00Z"));
        JsonNode m = json(call(get("/api/appointments/metrics?from=" + day + "&to=" + day)));
        assertEquals(5, m.get("total").asInt());
        assertEquals(1, m.get("scheduled").asInt());
        assertEquals(1, m.get("ready").asInt());
        assertEquals(1, m.get("needsReview").asInt());
        assertEquals(1, m.get("cancelled").asInt());
        assertEquals(1, m.get("completed").asInt());
        assertEquals(0, m.get("noShow").asInt());
        assertEquals(2, m.get("questionnairesCompleted").asInt());
        assertEquals(1, m.get("questionnaireMissing").asInt());
        assertEquals(0.5, m.get("completionRate").asDouble(), 1e-9);
        assertEquals(day, m.get("from").asText());

        clock.reset();
        JsonNode only = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2027-04-01T09:00:00");
        setStatus(id(only), "CANCELLED");
        JsonNode m2 = json(call(get("/api/appointments/metrics?from=2027-04-01&to=2027-04-01")));
        assertEquals(1, m2.get("total").asInt());
        assertTrue(m2.get("completionRate").isNull());
        assertEquals(400, call(get("/api/appointments/metrics?from=2027-04-02&to=2027-04-01")).getResponse().getStatus());
    }

    @Test
    void update() throws Exception {
        long pid = newPatient();
        JsonNode a = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-20T09:00:00");
        long id = id(a);
        JsonNode ok = json(put(id, "GENERAL_CONSULT", "2026-10-20T10:00:00"));
        assertEquals("2026-10-20T10:00:00", ok.get("scheduledAt").asText());
        assertEquals(45, ok.get("durationMinutes").asInt());
        assertEquals("Room 3", ok.get("location").asText());
        JsonNode repinned = json(put(id, "SURGERY", "2026-10-20T10:00:00"));
        assertTrue(repinned.get("questionnaireUrl").asText().endsWith("/pre-surgery"));
        assertEquals(400, put(id, "SURGERY", "2026-10-01T10:00:00").getResponse().getStatus());

        intake(a, true, false);
        MvcResult typeChange = put(id, "GENERAL_CONSULT", "2026-10-20T10:00:00");
        assertEquals(409, typeChange.getResponse().getStatus());
        assertEquals("INVALID_STATE", json(typeChange).get("error").asText());
        assertEquals(200, put(id, "SURGERY", "2026-10-20T11:00:00").getResponse().getStatus());

        JsonNode c = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-21T09:00:00");
        setStatus(id(c), "CANCELLED");
        assertEquals(409, put(id(c), "GENERAL_CONSULT", "2026-10-21T09:00:00").getResponse().getStatus());
        assertEquals(404, put(99999999, "GENERAL_CONSULT", "2026-10-21T09:00:00").getResponse().getStatus());
    }

    @Test
    void deleteRules() throws Exception {
        long pid = newPatient();
        JsonNode a = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-20T09:00:00");
        assertEquals(204, call(delete("/api/appointments/" + id(a))).getResponse().getStatus());
        assertEquals(404, call(get("/api/appointments/" + id(a))).getResponse().getStatus());
        assertEquals(404, mockMvc.perform(get("/api/public/q/" + a.get("accessCode").asText())).andReturn().getResponse().getStatus());
        JsonNode b = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-20T09:00:00");
        intake(b, false, false);
        assertEquals(409, call(delete("/api/appointments/" + id(b))).getResponse().getStatus());
        JsonNode c = newAppointment(pid, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-20T09:00:00");
        setStatus(id(c), "CANCELLED");
        assertEquals(409, call(delete("/api/appointments/" + id(c))).getResponse().getStatus());
        assertEquals(404, call(delete("/api/appointments/99999999")).getResponse().getStatus());
    }

    @Test
    void reviewAndStatusAndRegenerate() throws Exception {
        long pid = newPatient();
        JsonNode a = newAppointment(pid, "SURGERY", "ORTHOPEDIC_SURGERY", "2026-10-20T09:00:00");
        long id = id(a);
        assertEquals(409, call(post("/api/appointments/" + id + "/review")).getResponse().getStatus());
        String oldCode = a.get("accessCode").asText();
        JsonNode regen = json(call(post("/api/appointments/" + id + "/access-code")));
        assertNotEquals(oldCode, regen.get("accessCode").asText());
        assertEquals(404, mockMvc.perform(get("/api/public/q/" + oldCode)).andReturn().getResponse().getStatus());
        assertEquals(200, mockMvc.perform(get("/api/public/q/" + regen.get("accessCode").asText())).andReturn().getResponse().getStatus());

        mockMvc.perform(post("/api/public/q/" + regen.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(intakeBody(SURGERY_URL, true, true, true))).andExpect(status().isCreated());
        JsonNode d = detail(id);
        assertEquals("NEEDS_REVIEW", d.get("status").asText());
        assertTrue(d.get("intake").get("response").isObject());
        JsonNode flag = d.get("intake").get("flags").get(0);
        assertEquals("Do you take a blood thinner (e.g. warfarin/Coumadin, apixaban/Eliquis, rivaroxaban/Xarelto, clopidogrel/Plavix)?", flag.get("label").asText());
        assertFalse(flag.get("labelFr").asText().isBlank());
        assertNotEquals(flag.get("label").asText(), flag.get("labelFr").asText());
        assertFalse(flag.get("messageFr").asText().isBlank());
        assertEquals(409, call(post("/api/appointments/" + id + "/access-code")).getResponse().getStatus());

        JsonNode reviewed = json(call(post("/api/appointments/" + id + "/review")));
        assertEquals("READY", reviewed.get("status").asText());
        assertEquals("auth0|nurse-a", reviewed.get("reviewedBy").asText());
        assertEquals(409, call(post("/api/appointments/" + id + "/review")).getResponse().getStatus());

        MvcResult bad = setStatus(id, "READY");
        assertEquals(400, bad.getResponse().getStatus());
        assertTrue(json(bad).get("fieldErrors").has("status"));
        assertEquals(200, setStatus(id, "COMPLETED").getResponse().getStatus());
        assertEquals(409, setStatus(id, "CANCELLED").getResponse().getStatus());
    }

    @Test
    void cancelledAndRequireJwt() throws Exception {
        JsonNode a = newAppointment(newPatient(), "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", "2026-10-20T09:00:00");
        assertEquals(200, setStatus(id(a), "CANCELLED").getResponse().getStatus());
        assertEquals(409, call(post("/api/appointments/" + id(a) + "/access-code")).getResponse().getStatus());
        mockMvc.perform(get("/api/appointments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/appointments/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/appointments/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/patients/1/appointments")).andExpect(status().isUnauthorized());
    }
}
