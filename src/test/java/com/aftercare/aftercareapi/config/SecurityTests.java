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
                "/api/questionnaires", "/api/patients/1/follow-ups", "/api/patients/1/check-ins"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
