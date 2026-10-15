package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class MedicalItemsTests extends IntegrationTest {

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String body) throws Exception {
        return call(body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode medical(long id) throws Exception {
        return json(call(get("/api/patients/" + id + "/medical")));
    }

    private void crud(String list, String create, String update, String field, String updatedValue, String nameField) throws Exception {
        long p = newPatient();
        long other = newPatient();
        String base = "/api/patients/" + p + "/" + list;
        MvcResult c = send(post(base), create);
        assertEquals(201, c.getResponse().getStatus(), c.getResponse().getContentAsString());
        long itemId = json(c).get("id").asLong();
        assertEquals(1, medical(p).get(list).size());

        MvcResult u = send(put(base + "/" + itemId), update);
        assertEquals(200, u.getResponse().getStatus(), u.getResponse().getContentAsString());
        assertEquals(updatedValue, json(u).get(field).asText());

        // an item of another patient is a 404
        assertEquals(404, send(put("/api/patients/" + other + "/" + list + "/" + itemId), update).getResponse().getStatus());
        assertEquals(404, send(delete("/api/patients/" + other + "/" + list + "/" + itemId), null).getResponse().getStatus());

        assertEquals(204, send(delete(base + "/" + itemId), null).getResponse().getStatus());
        assertEquals(0, medical(p).get(list).size());
        assertEquals(1, jdbc.queryForObject("select count(*) from " + table(list) + " where id = ? and deleted_at is not null", Long.class, itemId));
        assertEquals(404, send(delete(base + "/" + itemId), null).getResponse().getStatus());
        assertEquals(404, send(put(base + "/" + itemId), update).getResponse().getStatus());
        assertEquals(1, auditCount(p, "MEDICAL_ITEM_REMOVED"));
        assertEquals(1, auditCount(p, "MEDICAL_ITEM_ADDED"));
        assertEquals(1, auditCount(p, "MEDICAL_ITEM_UPDATED"));
    }

    private static String table(String list) {
        return switch (list) {
            case "allergies" -> "patient_allergies";
            case "medications" -> "patient_medications";
            case "procedures" -> "patient_procedures";
            default -> "patient_diagnoses";
        };
    }

    @Test
    void allergiesCrud() throws Exception {
        crud("allergies", "{\"substance\":\"Penicillin\",\"severity\":\"SEVERE\",\"reaction\":null}",
                "{\"substance\":\"Latex\",\"severity\":\"MILD\",\"reaction\":\"Rash\"}", "substance", "Latex", "substance");
    }

    @Test
    void medicationsCrud() throws Exception {
        crud("medications", "{\"name\":\"Metformin\",\"dose\":\"500 mg\",\"frequency\":null,\"startDate\":null}",
                "{\"name\":\"Apixaban\",\"dose\":null,\"frequency\":\"BID\",\"startDate\":\"2026-10-01\"}", "name", "Apixaban", "name");
    }

    @Test
    void proceduresCrud() throws Exception {
        crud("procedures", "{\"name\":\"Appendectomy\",\"performedOn\":\"2026-10-01\",\"surgeonStaffId\":null,\"surgeonName\":\"Dr Free\"}",
                "{\"name\":\"Cholecystectomy\",\"performedOn\":\"2026-10-02\",\"surgeonStaffId\":null,\"surgeonName\":null}", "name", "Cholecystectomy", "name");
    }

    @Test
    void diagnosesCrud() throws Exception {
        crud("diagnoses", "{\"description\":\"Diabetes\",\"icd10Code\":\"E11.9\",\"diagnosedOn\":null}",
                "{\"description\":\"Gallstones\",\"icd10Code\":null,\"diagnosedOn\":\"2026-09-01\"}", "description", "Gallstones", "description");
    }

    @Test
    void allergiesAreOrderedBySeverityThenSubstance() throws Exception {
        long p = newPatient();
        String base = "/api/patients/" + p + "/allergies";
        for (String[] a : new String[][]{{"Zinc", "MILD"}, {"Pollen", "UNKNOWN"}, {"Latex", "SEVERE"}, {"Aspirin", "SEVERE"}, {"Nuts", "MODERATE"}}) {
            assertEquals(201, send(post(base), "{\"substance\":\"" + a[0] + "\",\"severity\":\"" + a[1] + "\"}").getResponse().getStatus());
        }
        JsonNode list = medical(p).get("allergies");
        String[] expected = {"Aspirin", "Latex", "Nuts", "Zinc", "Pollen"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], list.get(i).get("substance").asText());
        }
    }

    @Test
    void procedureWithInactiveSurgeonIsRejectedAndActiveOneShowsName() throws Exception {
        long p = newPatient();
        long active = newStaff("Dr Active " + uniq(), "SURGEON", null, null);
        long inactive = newStaff("Dr Inactive " + uniq(), "SURGEON", null, null);
        assertEquals(200, call(post("/api/staff/" + inactive + "/deactivate")).getResponse().getStatus());
        String base = "/api/patients/" + p + "/procedures";
        MvcResult bad = send(post(base), "{\"name\":\"X\",\"performedOn\":\"2026-10-01\",\"surgeonStaffId\":" + inactive + ",\"surgeonName\":null}");
        assertEquals(409, bad.getResponse().getStatus());
        assertEquals("STAFF_INACTIVE", json(bad).get("error").asText());
        assertEquals(404, send(post(base), "{\"name\":\"X\",\"performedOn\":\"2026-10-01\",\"surgeonStaffId\":987654,\"surgeonName\":null}").getResponse().getStatus());
        MvcResult ok = send(post(base), "{\"name\":\"X\",\"performedOn\":\"2026-10-01\",\"surgeonStaffId\":" + active + ",\"surgeonName\":\"ignored\"}");
        assertEquals(201, ok.getResponse().getStatus());
        assertEquals(active, json(ok).get("surgeonStaffId").asLong());
        assertTrue(json(ok).get("surgeonName").asText().startsWith("Dr Active"));
    }

    @Test
    void icd10IsUpperCasedAndValidated() throws Exception {
        long p = newPatient();
        String base = "/api/patients/" + p + "/diagnoses";
        MvcResult ok = send(post(base), "{\"description\":\"D\",\"icd10Code\":\"k80.20\",\"diagnosedOn\":null}");
        assertEquals(201, ok.getResponse().getStatus());
        assertEquals("K80.20", json(ok).get("icd10Code").asText());
        MvcResult bad = send(post(base), "{\"description\":\"D\",\"icd10Code\":\"123\",\"diagnosedOn\":null}");
        assertEquals(400, bad.getResponse().getStatus());
        assertTrue(json(bad).get("fieldErrors").has("icd10Code"));
        MvcResult future = send(post(base), "{\"description\":\"D\",\"icd10Code\":null,\"diagnosedOn\":\"2030-01-01\"}");
        assertEquals(400, future.getResponse().getStatus());
    }
}
