package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityTests extends IntegrationTest {

    @Test
    void devTokenAbsentOutsideLocal() throws Exception {
        mockMvc.perform(get("/dev/token")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicPreflightIsAllowed() throws Exception {
        mockMvc.perform(options("/api/public/q/x")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"));
    }

    @Test
    void publicEndpointsNeverRequireNorAcceptAJwt() throws Exception {
        mockMvc.perform(get("/api/public/q/x")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mockMvc.perform(post("/api/public/q/x").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        // a bogus bearer token is ignored rather than rejected
        mockMvc.perform(get("/api/public/q/x").header("Authorization", "Bearer garbage")).andExpect(status().isNotFound());
        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void staffEndpointsRequireJwt() throws Exception {
        for (String path : new String[]{"/api/patients", "/api/alerts", "/api/appointments", "/api/appointments/metrics",
                "/api/questionnaires", "/api/patients/1/follow-ups", "/api/patients/1/check-ins", "/api/check-ins"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void newStaffPathsRequireJwt() throws Exception {
        String[] gets = {"/api/patients/1/medical", "/api/patients/1/emergency-contacts", "/api/patients/1/care-team",
                "/api/patients/1/timeline", "/api/patients/1/documents", "/api/documents/1/content", "/api/patients/1/audit",
                "/api/patients/1/messages", "/api/messages/inbox", "/api/messages/unread-count", "/api/staff",
                "/api/settings/clinic", "/api/reports/summary"};
        for (String path : gets) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        String[] posts = {"/api/patients/1/notes", "/api/notes/1/delete", "/api/documents/1/delete", "/api/patients/1/messages",
                "/api/patients/1/messages/read", "/api/staff", "/api/staff/1/deactivate", "/api/staff/1/reactivate",
                "/api/patients/1/health-number/reveal", "/api/patients/1/allergies", "/api/patients/1/care-team"};
        for (String path : posts) {
            mockMvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(multipart("/api/patients/1/documents",
                        new org.springframework.mock.web.MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1".getBytes()), "OTHER", null))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/patients/1").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/notes/1").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/settings/clinic").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/patients/1/health-number").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/patients/1/health-number")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicMessageEndpointsWorkWithoutToken() throws Exception {
        mockMvc.perform(get("/api/public/q/x/messages")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Link is invalid or expired"));
        mockMvc.perform(post("/api/public/q/x/messages").contentType("application/json").content("{\"body\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/public/q/x/messages/read").contentType("application/json").content("{\"upToId\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/q/x/messages").header("Authorization", "Bearer garbage")).andExpect(status().isNotFound());
        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void corsExposesContentDisposition() throws Exception {
        mockMvc.perform(options("/api/documents/1/content")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, org.hamcrest.Matchers.containsString("Content-Disposition")));
    }
}
