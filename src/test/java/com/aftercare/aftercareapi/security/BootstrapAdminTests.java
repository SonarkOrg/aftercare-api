package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestPropertySource(properties = "aftercare.security.bootstrap-admin-sub=auth0|boot")
class BootstrapAdminTests extends IntegrationTest {

    private static final String BOOT = "auth0|boot";

    private static org.springframework.test.web.servlet.request.RequestPostProcessor bootToken() {
        return jwt().jwt(j -> j.subject(BOOT).claim("name", "First Admin").claim("email", "First.Admin@Example.com"));
    }

    @BeforeEach
    void noAdminsLeft() {
        jdbc.update("delete from staff_members where auth0subject = ?", BOOT);
        jdbc.update("delete from audit_events where action = 'STAFF_BOOTSTRAPPED'");
        jdbc.update("update staff_members set active = false where access_role = 'ADMIN'");
    }

    @AfterEach
    void restoreAdmins() {
        jdbc.update("delete from staff_members where auth0subject = ?", BOOT);
        jdbc.update("update staff_members set active = true where auth0subject in (?, ?)", SUB_LEGACY, SUB_ADMIN);
    }

    private long bootstrapRows() {
        return jdbc.queryForObject("select count(*) from audit_events where action = 'STAFF_BOOTSTRAPPED'", Long.class);
    }

    @Test
    void firstRequestCreatesTheAdminOnceAndAuditsIt() throws Exception {
        MvcResult r = as(bootToken(), get("/api/me"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode me = json(r);
        assertEquals("ADMIN", me.get("accessRole").asText());
        assertEquals("First Admin", me.get("displayName").asText());
        assertEquals("first.admin@example.com", me.get("email").asText());
        assertEquals("OTHER", me.get("role").asText());
        assertEquals(1, bootstrapRows());
        var audit = jdbc.queryForMap("select actor_sub, actor_name, resource_type, patient_id, detail from audit_events where action = 'STAFF_BOOTSTRAPPED'");
        assertEquals(BOOT, audit.get("actor_sub"));
        assertEquals("First Admin", audit.get("actor_name"));
        assertEquals("STAFF", audit.get("resource_type"));
        assertNull(audit.get("patient_id"));
        assertNull(audit.get("detail"));

        // a second request finds the member: no second row, no second audit
        assertEquals(200, as(bootToken(), get("/api/me")).getResponse().getStatus());
        assertEquals(1, bootstrapRows());
        assertEquals(1, jdbc.queryForObject("select count(*) from staff_members where auth0subject = ?", Integer.class, BOOT));
        // the new admin can use admin endpoints
        assertEquals(200, as(bootToken(), get("/api/staff")).getResponse().getStatus());
    }

    @Test
    void withoutNameAndEmailClaimsTheDefaultsApply() throws Exception {
        MvcResult r = as(jwt().jwt(j -> j.subject(BOOT)), get("/api/me"));
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("Administrator", json(r).get("displayName").asText());
        assertTrue(json(r).get("email").isNull());
    }

    @Test
    void anotherSubIsNotStaff() throws Exception {
        MvcResult r = as(staffAs("auth0|boot2"), get("/api/me"));
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("NOT_STAFF", json(r).get("error").asText());
        assertEquals(0, bootstrapRows());
    }

    @Test
    void ignoredOnceAnyActiveAdminExists() throws Exception {
        jdbc.update("update staff_members set active = true where auth0subject = ?", SUB_ADMIN);
        MvcResult r = as(bootToken(), get("/api/me"));
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("NOT_STAFF", json(r).get("error").asText());
        assertEquals(0, bootstrapRows());
        assertEquals(0, jdbc.queryForObject("select count(*) from staff_members where auth0subject = ?", Integer.class, BOOT));
    }

    @Test
    void aDeactivatedMemberWithThatSubIsNotRecreated() throws Exception {
        ensureStaff(BOOT, "Former Admin", "ADMIN", false);
        MvcResult r = as(bootToken(), get("/api/me"));
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("NOT_STAFF", json(r).get("error").asText());
        assertEquals(0, bootstrapRows());
    }

    @Test
    void worksWhenTheOnlyAdminIsNotLinkedToALogin() throws Exception {
        ensureStaff("auth0|ghost-admin", "Ghost Admin", "ADMIN", true);
        jdbc.update("update staff_members set auth0subject = null where auth0subject = 'auth0|ghost-admin'");
        try {
            MvcResult r = as(bootToken(), get("/api/me"));
            assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
            assertEquals("ADMIN", json(r).get("accessRole").asText());
            assertEquals(1, bootstrapRows());
        } finally {
            jdbc.update("delete from staff_members where display_name = 'Ghost Admin'");
        }
    }

}
