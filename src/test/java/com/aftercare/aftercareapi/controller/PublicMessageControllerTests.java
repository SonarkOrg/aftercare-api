package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class PublicMessageControllerTests extends IntegrationTest {

    private MvcResult pSend(String code, String body) throws Exception {
        return mockMvc.perform(post("/api/public/q/" + code + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("body", body)))).andReturn();
    }

    private MvcResult pGet(String code, String query) throws Exception {
        return mockMvc.perform(get("/api/public/q/" + code + "/messages" + query)).andReturn();
    }

    private String code(long p) throws Exception {
        return newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null).get("accessCode").asText();
    }

    @Test
    void threadHasClinicNameAndNoStore() throws Exception {
        long p = newPatient();
        String code = code(p);
        MvcResult r = pGet(code, "");
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));
        String clinic = json(call(get("/api/settings/clinic"))).get("name").asText();
        assertEquals(clinic, json(r).get("clinicName").asText());
        assertEquals(0, json(r).get("items").size());
        assertEquals(0, json(r).get("unreadCount").asInt());
        assertEquals("no-store", pSend(code, "Bonjour").getResponse().getHeader("Cache-Control"));
    }

    @Test
    void patientSendAppearsUnreadForStaff() throws Exception {
        long p = newPatient();
        String code = code(p);
        long before = json(call(get("/api/messages/unread-count"))).get("unreadMessages").asLong();
        MvcResult r = pSend(code, "Puis-je prendre une douche?");
        assertEquals(201, r.getResponse().getStatus());
        assertEquals("PATIENT", json(r).get("sender").asText());
        assertEquals(before + 1, json(call(get("/api/messages/unread-count"))).get("unreadMessages").asLong());
        JsonNode item = json(call(get("/api/patients/" + p + "/messages"))).get("items").get(0);
        assertTrue(item.get("readByStaffAt").isNull());
        Map<String, Object> audit = auditRows(p).stream().filter(a -> "MESSAGE_SENT".equals(a.get("action"))).findFirst().orElseThrow();
        assertEquals("Patient", audit.get("actor_name"));
        assertTrue(audit.get("actor_sub").toString().startsWith("patient:link:"));
        assertEquals("PATIENT", audit.get("detail"));
        JsonNode tl = json(call(get("/api/patients/" + p + "/timeline").param("category", "MESSAGE"))).get("items").get(0);
        assertEquals("MESSAGE_FROM_PATIENT", tl.get("kind").asText());
        assertEquals("PATIENT", tl.get("actorType").asText());
    }

    @Test
    void hourlyRateLimit() throws Exception {
        long p = newPatient();
        String code = code(p);
        for (int i = 0; i < 10; i++) {
            assertEquals(201, pSend(code, "m" + i).getResponse().getStatus());
        }
        MvcResult limited = pSend(code, "eleventh");
        assertEquals(429, limited.getResponse().getStatus());
        assertEquals("RATE_LIMITED", json(limited).get("error").asText());
        assertEquals("Too many messages; try again later", json(limited).get("message").asText());
        clock.plus(Duration.ofMinutes(61));
        assertEquals(201, pSend(code, "later").getResponse().getStatus());
    }

    @Test
    void dailyRateLimit() throws Exception {
        long p = newPatient();
        String code = code(p);
        for (int batch = 0; batch < 3; batch++) {
            for (int i = 0; i < 10; i++) {
                assertEquals(201, pSend(code, "b" + batch + "m" + i).getResponse().getStatus());
            }
            clock.plus(Duration.ofMinutes(61));
        }
        assertEquals(429, pSend(code, "thirty-first").getResponse().getStatus());
        clock.plus(Duration.ofHours(23));
        assertEquals(201, pSend(code, "next day").getResponse().getStatus());
    }

    @Test
    void unusableCodesGetTheSame404AsQ() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        String old = f.get("accessCode").asText();
        String q404 = mockMvc.perform(get("/api/public/q/" + "z".repeat(43))).andReturn().getResponse().getContentAsString();
        for (String bad : new String[]{"z".repeat(43), "short", "!!!"}) {
            MvcResult g = pGet(bad, "");
            assertEquals(404, g.getResponse().getStatus());
            assertEquals(q404, g.getResponse().getContentAsString());
            assertEquals(404, pSend(bad, "x").getResponse().getStatus());
        }
        assertEquals("Link is invalid or expired", json(pGet("z".repeat(43), "")).get("message").asText());
        // revoked by regeneration
        call(post("/api/follow-ups/" + f.get("followUp").get("id").asLong() + "/access-code"));
        assertEquals(404, pGet(old, "").getResponse().getStatus());
        assertEquals(404, pSend(old, "x").getResponse().getStatus());
        // expired
        String fresh = json(call(post("/api/follow-ups/" + f.get("followUp").get("id").asLong() + "/access-code"))).get("accessCode").asText();
        assertEquals(200, pGet(fresh, "").getResponse().getStatus());
        clock.set(Instant.parse("2026-10-30T16:00:00Z"));
        assertEquals(404, pGet(fresh, "").getResponse().getStatus());
        assertEquals(404, pSend(fresh, "x").getResponse().getStatus());
    }

    @Test
    void authorizationHeaderIsIgnored() throws Exception {
        long p = newPatient();
        String code = code(p);
        assertEquals(200, mockMvc.perform(get("/api/public/q/" + code + "/messages").header("Authorization", "Bearer garbage")).andReturn().getResponse().getStatus());
        assertEquals(201, mockMvc.perform(post("/api/public/q/" + code + "/messages").header("Authorization", "Bearer garbage")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}")).andReturn().getResponse().getStatus());
    }

    @Test
    void responseNeverLeaksInternalFields() throws Exception {
        long p = newPatient();
        String code = code(p);
        pSend(code, "from patient");
        call(post("/api/patients/" + p + "/messages").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"from staff\"}"));
        MvcResult r = pGet(code, "");
        String raw = r.getResponse().getContentAsString();
        for (String forbidden : new String[]{"staffSub", "accessLinkId", "patientId", "firstName", "lastName", "readByStaff", "readByPatient"}) {
            assertFalse(raw.contains(forbidden), forbidden);
        }
        JsonNode items = json(r).get("items");
        assertEquals(2, items.size());
        for (JsonNode item : items) {
            Set<String> names = new HashSet<>();
            Iterator<String> it = item.fieldNames();
            it.forEachRemaining(names::add);
            assertEquals(Set.of("id", "sender", "senderName", "body", "sentAt", "seen"), names);
        }
        assertTrue(items.get(0).get("senderName").isNull());
    }

    @Test
    void seenFlagsAndPatientMarkRead() throws Exception {
        long p = newPatient();
        String code = code(p);
        long mine = json(pSend(code, "from patient")).get("id").asLong();
        long theirs = json(call(post("/api/patients/" + p + "/messages").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"from staff\"}"))).get("id").asLong();

        JsonNode t = json(pGet(code, ""));
        assertEquals(1, t.get("unreadCount").asInt());
        assertFalse(t.get("items").get(0).get("seen").asBoolean());
        assertFalse(t.get("items").get(1).get("seen").asBoolean());

        call(post("/api/patients/" + p + "/messages/read").contentType(MediaType.APPLICATION_JSON).content("{\"upToId\":" + mine + "}"));
        assertEquals(204, mockMvc.perform(post("/api/public/q/" + code + "/messages/read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"upToId\":" + theirs + "}")).andReturn().getResponse().getStatus());

        JsonNode after = json(pGet(code, ""));
        assertEquals(0, after.get("unreadCount").asInt());
        assertTrue(after.get("items").get(0).get("seen").asBoolean());
        assertTrue(after.get("items").get(1).get("seen").asBoolean());
        assertEquals(400, mockMvc.perform(post("/api/public/q/" + code + "/messages/read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"upToId\":0}")).andReturn().getResponse().getStatus());
        assertEquals(404, mockMvc.perform(post("/api/public/q/" + "z".repeat(43) + "/messages/read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"upToId\":1}")).andReturn().getResponse().getStatus());
    }

    @Test
    void bodyAndLimitValidation() throws Exception {
        long p = newPatient();
        String code = code(p);
        assertEquals(400, pSend(code, "a\u0000b").getResponse().getStatus());
        assertEquals(400, pSend(code, "a".repeat(2001)).getResponse().getStatus());
        assertEquals(400, pGet(code, "?limit=0").getResponse().getStatus());
    }
}
