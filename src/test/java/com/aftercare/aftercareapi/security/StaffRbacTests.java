package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StaffRbacTests extends IntegrationTest {

    @AfterEach
    void restoreAdmins() {
        jdbc.update("update staff_members set active = true, access_role = 'ADMIN', role = 'OTHER' where auth0subject in (?, ?)",
                SUB_LEGACY, SUB_ADMIN);
    }

    private String body(String name, String accessRole, String sub) {
        return "{\"displayName\":\"" + name + "\",\"role\":\"NURSE\"" + (accessRole == null ? "" : ",\"accessRole\":\"" + accessRole + "\"")
                + ",\"email\":null,\"auth0Subject\":" + (sub == null ? "null" : "\"" + sub + "\"") + "}";
    }

    @Test
    void onlyAdminsManageStaff() throws Exception {
        long id = newStaff("Target " + uniq(), "NURSE", null, null);
        for (AccessRole role : List.of(AccessRole.PHYSICIAN, AccessRole.NURSE, AccessRole.RECEPTION)) {
            var who = asRole(role);
            assertEquals(403, as(who, post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(body("X", "NURSE", null))).getResponse().getStatus());
            assertEquals(403, as(who, put("/api/staff/" + id).contentType(MediaType.APPLICATION_JSON).content(body("X", "NURSE", null))).getResponse().getStatus());
            assertEquals(403, as(who, post("/api/staff/" + id + "/deactivate")).getResponse().getStatus());
            assertEquals(403, as(who, post("/api/staff/" + id + "/reactivate")).getResponse().getStatus());
        }
    }

    @Test
    void loginSubjectsAreVisibleToAdminsOnly() throws Exception {
        String u = uniq();
        long id = newStaff("Linked " + u, "NURSE", null, "auth0|rbac-" + u);
        for (AccessRole role : AccessRole.values()) {
            JsonNode list = json(as(asRole(role), get("/api/staff")));
            JsonNode me = null;
            for (JsonNode s : list) {
                if (s.get("id").asLong() == id) {
                    me = s;
                }
            }
            assertNotNull(me, role.name());
            assertTrue(me.has("auth0Subject"), "field is always present");
            assertEquals("NURSE", me.get("accessRole").asText());
            if (role == AccessRole.ADMIN) {
                assertEquals("auth0|rbac-" + u, me.get("auth0Subject").asText());
            } else {
                assertTrue(me.get("auth0Subject").isNull(), role.name());
            }
        }
    }

    @Test
    void accessRoleIsRequired() throws Exception {
        MvcResult r = call(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(body("No Role", null, null)));
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(json(r).get("fieldErrors").has("accessRole"));
        long id = newStaff("Role " + uniq(), "NURSE", null, null);
        assertEquals(400, call(put("/api/staff/" + id).contentType(MediaType.APPLICATION_JSON).content(body("Y", null, null))).getResponse().getStatus());
    }

    @Test
    void theLastAdminCannotBeDemotedOrDeactivated() throws Exception {
        // leave exactly one active admin: test|admin
        jdbc.update("update staff_members set active = false where access_role = 'ADMIN' and auth0subject <> ?", SUB_ADMIN);
        long adminId = jdbc.queryForObject("select id from staff_members where auth0subject = ?", Long.class, SUB_ADMIN);
        MvcResult demote = as(asRole(AccessRole.ADMIN), put("/api/staff/" + adminId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Test Admin", "NURSE", SUB_ADMIN)));
        assertEquals(409, demote.getResponse().getStatus());
        assertEquals("LAST_ADMIN", json(demote).get("error").asText());
        assertEquals("At least one active administrator is required", json(demote).get("message").asText());
        MvcResult off = as(asRole(AccessRole.ADMIN), post("/api/staff/" + adminId + "/deactivate"));
        assertEquals(409, off.getResponse().getStatus());
        assertEquals("LAST_ADMIN", json(off).get("error").asText());
        // staying admin (rename) is fine
        assertEquals(200, as(asRole(AccessRole.ADMIN), put("/api/staff/" + adminId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Test Admin", "ADMIN", SUB_ADMIN))).getResponse().getStatus());
        assertEquals("ADMIN", jdbc.queryForObject("select access_role from staff_members where id = ?", String.class, adminId));
    }

    @Test
    void oneOfTwoAdminsCanBeDemotedAndDeactivatesHimself() throws Exception {
        String u = uniq();
        long second = newStaff("Second Admin " + u, "OTHER", "ADMIN", null, "auth0|second-" + u);
        // demoting one of two admins works
        MvcResult ok = call(put("/api/staff/" + second).contentType(MediaType.APPLICATION_JSON)
                .content(body("Second Admin " + u, "NURSE", "auth0|second-" + u)));
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());
        assertEquals("NURSE", json(ok).get("accessRole").asText());
        // reactivation and promotion need no guard
        assertEquals(200, call(put("/api/staff/" + second).contentType(MediaType.APPLICATION_JSON)
                .content(body("Second Admin " + u, "ADMIN", "auth0|second-" + u))).getResponse().getStatus());
        // an admin may deactivate himself when another admin remains; his next request is NOT_STAFF
        var self = staffAs("auth0|second-" + u);
        assertEquals(200, as(self, post("/api/staff/" + second + "/deactivate")).getResponse().getStatus());
        MvcResult after = as(self, get("/api/me"));
        assertEquals(403, after.getResponse().getStatus());
        assertEquals("NOT_STAFF", json(after).get("error").asText());
    }

    @Test
    void aRoleChangeIsAuditedWithOldAndNewRole() throws Exception {
        String u = uniq();
        long id = newStaff("Promoted " + u, "NURSE", "NURSE", null, null);
        assertEquals(200, call(put("/api/staff/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("Promoted " + u, "ADMIN", null))).getResponse().getStatus());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select action, detail, resource_type, patient_id from audit_events where resource_id = ? and action in ('STAFF_ROLE_CHANGED','STAFF_UPDATED') order by id", id);
        assertEquals(2, rows.size());
        assertEquals("STAFF_UPDATED", rows.get(0).get("action"));
        assertTrue(rows.get(0).get("detail").toString().contains("accessRole"));
        assertEquals("STAFF_ROLE_CHANGED", rows.get(1).get("action"));
        assertEquals("NURSE->ADMIN", rows.get(1).get("detail"));
        assertEquals("STAFF", rows.get(1).get("resource_type"));
        assertNull(rows.get(1).get("patient_id"));
        // no role change, no STAFF_ROLE_CHANGED
        assertEquals(200, call(put("/api/staff/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(body("Renamed " + u, "ADMIN", null))).getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where resource_id = ? and action = 'STAFF_ROLE_CHANGED'", Integer.class, id));
    }

    private void insertAdmin(String name, String sub) {
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("insert into staff_members (display_name, role, access_role, auth0subject, active, created_at, created_by_sub, "
                + "updated_at, updated_by_sub, version) values (?, 'OTHER', 'ADMIN', ?, true, ?, 'test', ?, 'test', 0)", name, sub, now, now);
    }

    @Test
    void theLastLinkedAdminCannotUnlinkOrRelinkHimself() throws Exception {
        jdbc.update("update staff_members set active = false where access_role = 'ADMIN' and auth0subject <> ?", SUB_ADMIN);
        long adminId = jdbc.queryForObject("select id from staff_members where auth0subject = ?", Long.class, SUB_ADMIN);
        MvcResult unlink = as(asRole(AccessRole.ADMIN), put("/api/staff/" + adminId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Test Admin", "ADMIN", null)));
        assertEquals(409, unlink.getResponse().getStatus());
        assertEquals("LAST_ADMIN", json(unlink).get("error").asText());
        MvcResult relink = as(asRole(AccessRole.ADMIN), put("/api/staff/" + adminId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Test Admin", "ADMIN", "auth0|elsewhere-" + uniq())));
        assertEquals(409, relink.getResponse().getStatus());
        assertEquals("LAST_ADMIN", json(relink).get("error").asText());
        assertEquals(SUB_ADMIN, jdbc.queryForObject("select auth0subject from staff_members where id = ?", String.class, adminId));
        // an unlinked second admin does not count as "another admin": the linked one is still the last
        insertAdmin("Unlinked Admin " + uniq(), null);
        assertEquals(409, as(asRole(AccessRole.ADMIN), post("/api/staff/" + adminId + "/deactivate")).getResponse().getStatus());
        // with a second linked admin the first may unlink himself
        insertAdmin("Second " + uniq(), "auth0|second-linked-" + uniq());
        assertEquals(200, as(asRole(AccessRole.ADMIN), put("/api/staff/" + adminId).contentType(MediaType.APPLICATION_JSON)
                .content(body("Test Admin", "ADMIN", null))).getResponse().getStatus());
        jdbc.update("update staff_members set auth0subject = ? where id = ?", SUB_ADMIN, adminId);
        jdbc.update("update staff_members set active = false where display_name like 'Unlinked Admin%' or display_name like 'Second %'");
    }

}
