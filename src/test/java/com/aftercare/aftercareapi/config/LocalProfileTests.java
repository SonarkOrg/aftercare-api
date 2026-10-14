package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.Enums.AlertStatus;
import com.aftercare.aftercareapi.Enums.AppointmentStatus;
import com.aftercare.aftercareapi.Enums.FollowUpStatus;
import com.aftercare.aftercareapi.Repositories.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:localprofile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalProfileTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper om;
    @Autowired PatientRepository patients;
    @Autowired FollowUpRepository followUps;
    @Autowired AppointmentRepository appointments;
    @Autowired AlertRepository alerts;
    @Autowired LocalDataSeeder seeder;

    private String token() throws Exception {
        String body = mockMvc.perform(get("/dev/token")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(28800))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("accessToken").asText();
    }

    @Test
    void devTokenReadsStaffEndpoints() throws Exception {
        String t = token();
        mockMvc.perform(get("/api/patients").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/patients")).andExpect(status().isUnauthorized());
    }

    @Test
    void foreignSecretIsRejected() throws Exception {
        NimbusJwtEncoder enc = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec("some-other-secret-that-is-long-enough-123".getBytes(), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("http://localhost:8080/dev").audience(List.of("aftercare-api-local"))
                .subject("evil").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        String forged = enc.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mockMvc.perform(get("/api/patients").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void seedContents() {
        assertEquals(2, followUps.findByStatus(FollowUpStatus.ACTIVE).size());
        assertEquals(7, appointments.count());
        Set<AppointmentStatus> statuses = appointments.findAll().stream().map(a -> a.getStatus()).collect(Collectors.toSet());
        assertEquals(EnumSet.allOf(AppointmentStatus.class), statuses);
        assertEquals(4, alerts.count());
        assertEquals(1, alerts.findByStatusIn(EnumSet.of(AlertStatus.ACKNOWLEDGED)).size());
    }

    @Test
    void seededCodeWorksOnPublicEndpoint() throws Exception {
        mockMvc.perform(get("/api/public/q/" + LocalDataSeeder.MARIE_CODE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("FOLLOW_UP_CHECKIN"))
                .andExpect(jsonPath("$.submitted").value(false));
        mockMvc.perform(get("/api/public/q/" + LocalDataSeeder.JEAN_INTAKE_CODE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("APPOINTMENT_INTAKE"));
    }

    @Test
    void rerunningSeederIsNoOp() {
        long p = patients.count();
        long a = appointments.count();
        seeder.run(null);
        assertEquals(p, patients.count());
        assertEquals(a, appointments.count());
        assertEquals(4, alerts.count());
    }
}
