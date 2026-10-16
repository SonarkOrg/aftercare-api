package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Services.ActorResolver;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StaffControllerTests extends IntegrationTest {

    private String body(String name, String email, String sub) {
        return "{\"displayName\":\"" + name + "\",\"role\":\"NURSE\",\"accessRole\":\"NURSE\",\"email\":" + (email == null ? "null" : "\"" + email + "\"")
                + ",\"auth0Subject\":" + (sub == null ? "null" : "\"" + sub + "\"") + "}";
    }

    private MvcResult postStaff(String body) throws Exception {
        return call(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void createUpdateDeactivateReactivate() throws Exception {
        String u = uniq();
        MvcResult c = postStaff(body("Nurse " + u, "Mixed." + u + "@Example.com", "auth0|s-" + u));
        assertEquals(201, c.getResponse().getStatus());
        JsonNode s = json(c);
        long id = s.get("id").asLong();
        assertEquals("mixed." + u + "@example.com", s.get("email").asText());
        assertTrue(s.get("active").asBoolean());

        MvcResult up = call(put("/api/staff/" + id).contentType(MediaType.APPLICATION_JSON).content(body("Renamed " + u, "mixed." + u + "@example.com", "auth0|s-" + u)));
        assertEquals(200, up.getResponse().getStatus());
        assertEquals("Renamed " + u, json(up).get("displayName").asText());
        assertEquals(404, call(put("/api/staff/987654").contentType(MediaType.APPLICATION_JSON).content(body("X", null, null))).getResponse().getStatus());

        assertEquals(200, call(post("/api/staff/" + id + "/deactivate")).getResponse().getStatus());
        MvcResult again = call(post("/api/staff/" + id + "/deactivate"));
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("INVALID_STATE", json(again).get("error").asText());

        assertFalse(ids(json(call(get("/api/staff")))).contains(id));
        assertTrue(ids(json(call(get("/api/staff").param("includeInactive", "true")))).contains(id));

        assertEquals(200, call(post("/api/staff/" + id + "/reactivate")).getResponse().getStatus());
        assertEquals(409, call(post("/api/staff/" + id + "/reactivate")).getResponse().getStatus());
        assertEquals(400, call(get("/api/staff").param("includeInactive", "maybe")).getResponse().getStatus());
    }

    @Test
    void duplicateEmailOrSubjectIsRejected() throws Exception {
        String u = uniq();
        assertEquals(201, postStaff(body("A " + u, "dup." + u + "@example.com", "auth0|dup-" + u)).getResponse().getStatus());
        MvcResult e = postStaff(body("B " + u, "DUP." + u + "@EXAMPLE.com", null));
        assertEquals(409, e.getResponse().getStatus());
        assertEquals("DUPLICATE_STAFF", json(e).get("error").asText());
        assertEquals(409, postStaff(body("C " + u, null, "auth0|dup-" + u)).getResponse().getStatus());
        long other = newStaff("D " + u, "NURSE", null, null);
        assertEquals(409, call(put("/api/staff/" + other).contentType(MediaType.APPLICATION_JSON)
                .content(body("D " + u, "dup." + u + "@example.com", null))).getResponse().getStatus());
    }

    @Test
    void validation() throws Exception {
        assertEquals(400, postStaff(body("", null, null)).getResponse().getStatus());
        MvcResult e = postStaff(body("X", "not-an-email", null));
        assertTrue(json(e).get("fieldErrors").has("email"));
        assertEquals(400, postStaff(body("X", null, "has space")).getResponse().getStatus());
    }

    @Test
    void orderingIsAccentInsensitive() throws Exception {
        String u = uniq();
        long fanny = newStaff("Fanny " + u, "NURSE", null, null);
        long emilie = newStaff("Émilie " + u, "NURSE", null, null);
        List<Long> ids = ids(json(call(get("/api/staff"))));
        assertTrue(ids.indexOf(emilie) < ids.indexOf(fanny));
    }

    @Test
    void actorNameComesFromTheDirectoryAndUnlinkedLoginsAreRejected() throws Exception {
        long p = newPatient();
        String u = uniq();
        newStaff("Linked Nurse " + u, "NURSE", null, "auth0|linked-" + u);
        String note = "{\"body\":\"hello\",\"notedAt\":null}";
        String url = "/api/patients/" + p + "/notes";
        // the directory name wins over the token's name claim
        MvcResult linked = as(staffAs("auth0|linked-" + u, "Claim Name"), post(url).contentType(MediaType.APPLICATION_JSON).content(note));
        assertEquals("Linked Nurse " + u, json(linked).get("authorName").asText());
        // a login without an active staff member can no longer write anything
        MvcResult claim = as(staffAs("auth0|nolink-" + u, "Claim Name"), post(url).contentType(MediaType.APPLICATION_JSON).content(note));
        assertEquals(403, claim.getResponse().getStatus());
        assertEquals("NOT_STAFF", json(claim).get("error").asText());
    }

    @Autowired
    ActorResolver actorResolver;

    @Test
    void actorResolverFallsBackToTheNameClaimOutsideStaffRequests() {
        // used by non-request callers only (jobs, seeders): directory first, then the name claim, then null
        var claim = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
                .subject("auth0|nolink-x").claim("name", "Claim Name").build();
        assertEquals("Claim Name", actorResolver.from(claim).name());
        var none = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none").subject("auth0|noname-x").build();
        assertNull(actorResolver.from(none).name());
    }

    private static List<Long> ids(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }
}
