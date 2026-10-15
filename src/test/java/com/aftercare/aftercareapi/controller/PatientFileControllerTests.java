package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class PatientFileControllerTests extends IntegrationTest {

    private static String patientBody(String last, String dob) {
        return """
                {"firstName":"Test","lastName":"%s","email":"t@example.com","phone":"514-555-0100","gender":"female",
                 "address":{"city":"Montreal","state":"QC","zip":"H2X 1Y4"}%s}""".formatted(last, dob == null ? "" : ",\"dateOfBirth\":\"" + dob + "\"");
    }

    private MvcResult doPut(String url, String body) throws Exception {
        return call(put(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult doPost(String url, String body) throws Exception {
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void putUpdatesDateOfBirthAndAuditsFieldNames() throws Exception {
        long id = newPatient();
        MvcResult r = doPut("/api/patients/" + id, patientBody("Patient", "1980-05-05"));
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("1980-05-05", json(r).get("dateOfBirth").asText());
        assertEquals("1980-05-05", json(call(get("/api/patients/" + id))).get("dateOfBirth").asText());
        assertEquals(200, doPut("/api/patients/" + id, patientBody("Renamed", "1980-05-05")).getResponse().getStatus());
        List<Map<String, Object>> rows = auditRows(id);
        assertEquals("dateOfBirth", rows.get(0).get("detail"));
        assertEquals("lastName", rows.get(1).get("detail"));
        assertEquals(404, doPut("/api/patients/987654321", patientBody("X", null)).getResponse().getStatus());
    }

    @Test
    void futureOrTodayDateOfBirthIsRejected() throws Exception {
        long id = newPatient();
        for (String dob : new String[]{"2027-01-01", "2026-10-14", "1899-12-31"}) {
            MvcResult r = doPut("/api/patients/" + id, patientBody("Patient", dob));
            assertEquals(400, r.getResponse().getStatus(), dob);
            assertTrue(json(r).get("fieldErrors").has("dateOfBirth"));
        }
    }

    @Test
    void healthNumberLifecycle() throws Exception {
        long id = newPatient();
        assertTrue(json(call(get("/api/patients/" + id + "/medical"))).get("healthNumberMasked").isNull());

        MvcResult set = doPut("/api/patients/" + id + "/health-number", "{\"healthNumber\":\"trem 6854-1212\"}");
        assertEquals(200, set.getResponse().getStatus());
        assertEquals("TREM •••• ••12", json(set).get("healthNumberMasked").asText());
        String enc = jdbc.queryForObject("select health_number_enc from patients where id = ?", String.class, id);
        assertTrue(enc.startsWith("v1:"));
        assertFalse(enc.contains("TREM68541212"));
        assertEquals("TREM •••• ••12", json(call(get("/api/patients/" + id + "/medical"))).get("healthNumberMasked").asText());

        MvcResult bad = doPut("/api/patients/" + id + "/health-number", "{\"healthNumber\":\"ZZZ9999\"}");
        assertEquals(400, bad.getResponse().getStatus());
        assertEquals("must be a valid RAMQ number (4 letters, 8 digits)", json(bad).get("fieldErrors").get("healthNumber").asText());
        assertFalse(bad.getResponse().getContentAsString().contains("ZZZ9999"));

        assertEquals(400, doPost("/api/patients/" + id + "/health-number/reveal", "{\"reason\":\"\"}").getResponse().getStatus());
        assertEquals(400, doPost("/api/patients/" + id + "/health-number/reveal", "{}").getResponse().getStatus());
        MvcResult reveal = doPost("/api/patients/" + id + "/health-number/reveal", "{\"reason\":\"Billing check\"}");
        assertEquals(200, reveal.getResponse().getStatus());
        assertEquals("TREM68541212", json(reveal).get("healthNumber").asText());
        assertEquals("no-store", reveal.getResponse().getHeader("Cache-Control"));
        Map<String, Object> row = auditRows(id).stream().filter(x -> "HEALTH_NUMBER_REVEALED".equals(x.get("action"))).findFirst().orElseThrow();
        assertEquals("reason: Billing check", row.get("detail"));
        for (Map<String, Object> a : auditRows(id)) {
            assertFalse(String.valueOf(a.get("detail")).contains("TREM6854"));
        }

        assertEquals(204, call(delete("/api/patients/" + id + "/health-number")).getResponse().getStatus());
        MvcResult gone = doPost("/api/patients/" + id + "/health-number/reveal", "{\"reason\":\"Billing check\"}");
        assertEquals(404, gone.getResponse().getStatus());
        assertEquals("No health number on file", json(gone).get("message").asText());
        assertEquals(1, auditCount(id, "HEALTH_NUMBER_CLEARED"));
    }

    @Test
    void medicalViewIsAuditedOnlyWhenPatientExists() throws Exception {
        long id = newPatient();
        assertEquals(200, call(get("/api/patients/" + id + "/medical")).getResponse().getStatus());
        assertEquals(1, auditCount(id, "PATIENT_FILE_VIEWED"));
        long missing = 987654321L;
        assertEquals(404, call(get("/api/patients/" + missing + "/medical")).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where patient_id = ?", Long.class, missing));
    }
}
