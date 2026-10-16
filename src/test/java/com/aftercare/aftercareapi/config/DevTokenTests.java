package com.aftercare.aftercareapi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:devtoken;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DevTokenTests {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper om;

    private JsonNode token(String... params) throws Exception {
        var req = get("/dev/token");
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        MvcResult r = mockMvc.perform(req).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult me(JsonNode token) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token.get("accessToken").asText())).andReturn();
    }

    @Test
    void theDefaultIsTheSeededAdmin() throws Exception {
        JsonNode t = token();
        assertEquals("dev|admin-1", t.get("sub").asText());
        assertEquals("Bearer", t.get("tokenType").asText());
        assertEquals(28800, t.get("expiresIn").asInt());
        JsonNode me = om.readTree(me(t).getResponse().getContentAsString());
        assertEquals("ADMIN", me.get("accessRole").asText());
        assertEquals("Dev Admin", me.get("displayName").asText());
        assertEquals("dev.admin@example.com", me.get("email").asText());
    }

    @Test
    void eachRoleMapsToItsSeededStaffMember() throws Exception {
        String[][] expected = {{"ADMIN", "dev|admin-1", "Dev Admin"}, {"PHYSICIAN", "auth0|seed-surgeon", "Dr Alain Bouchard"},
                {"NURSE", "dev|nurse-1", "Dev Nurse"}, {"RECEPTION", "dev|reception-1", "Dev Reception"}};
        for (String[] e : expected) {
            JsonNode t = token("role", e[0]);
            assertEquals(e[1], t.get("sub").asText());
            MvcResult r = me(t);
            assertEquals(200, r.getResponse().getStatus(), e[0]);
            JsonNode me = om.readTree(r.getResponse().getContentAsString());
            assertEquals(e[0], me.get("accessRole").asText());
            assertEquals(e[2], me.get("displayName").asText());
        }
    }

    @Test
    void anUnlinkedSubGetsNotStaff() throws Exception {
        JsonNode t = token("sub", "dev|unlinked");
        assertEquals("dev|unlinked", t.get("sub").asText());
        MvcResult r = me(t);
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("NOT_STAFF", om.readTree(r.getResponse().getContentAsString()).get("error").asText());
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        MvcResult both = mockMvc.perform(get("/dev/token").param("role", "ADMIN").param("sub", "dev|x")).andReturn();
        assertEquals(400, both.getResponse().getStatus());
        JsonNode err = om.readTree(both.getResponse().getContentAsString());
        assertEquals("VALIDATION_FAILED", err.get("error").asText());
        assertTrue(err.get("fieldErrors").has("role") || err.get("fieldErrors").has("sub"));

        MvcResult badRole = mockMvc.perform(get("/dev/token").param("role", "ROOT")).andReturn();
        assertEquals(400, badRole.getResponse().getStatus());
        assertTrue(om.readTree(badRole.getResponse().getContentAsString()).get("fieldErrors").has("role"));

        for (String bad : new String[]{"has space", "x".repeat(65), "semi;colon", ""}) {
            MvcResult r = mockMvc.perform(get("/dev/token").param("sub", bad)).andReturn();
            assertEquals(400, r.getResponse().getStatus(), "sub=" + bad);
            assertTrue(om.readTree(r.getResponse().getContentAsString()).get("fieldErrors").has("sub"));
        }
    }
}
