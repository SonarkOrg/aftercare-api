package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class MessageControllerTests extends IntegrationTest {

    private MvcResult send(long p, String body) throws Exception {
        return call(post("/api/patients/" + p + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("body", body))));
    }

    private MvcResult patientSend(String code, String body) throws Exception {
        return mockMvc.perform(post("/api/public/q/" + code + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("body", body)))).andReturn();
    }

    private JsonNode thread(long p, String query) throws Exception {
        return json(call(get("/api/patients/" + p + "/messages" + query)));
    }

    private long unread() throws Exception {
        return json(call(get("/api/messages/unread-count"))).get("unreadMessages").asLong();
    }

    @Test
    void staffSendsWhileOpenAndNotAfterClose() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        MvcResult r = send(p, "Bonjour");
        assertEquals(201, r.getResponse().getStatus());
        assertEquals("STAFF", json(r).get("sender").asText());
        assertEquals(1, auditCount(p, "MESSAGE_SENT"));

        JsonNode t = thread(p, "");
        assertTrue(t.get("messagingOpen").asBoolean());
        // discharge 10-13 + 7 days = 10-20; usable until the start of 10-22 in Toronto (EDT)
        assertEquals("2026-10-22T04:00:00Z", t.get("messagingOpenUntil").asText());
        assertEquals(1, auditCount(p, "MESSAGES_VIEWED"));

        assertEquals(200, call(post("/api/follow-ups/" + f.get("followUp").get("id").asLong() + "/close")).getResponse().getStatus());
        MvcResult closed = send(p, "Encore");
        assertEquals(409, closed.getResponse().getStatus());
        assertEquals("MESSAGING_CLOSED", json(closed).get("error").asText());
        assertEquals("Messaging is closed: the patient has no active link", json(closed).get("message").asText());
        JsonNode after = thread(p, "");
        assertFalse(after.get("messagingOpen").asBoolean());
        assertTrue(after.get("messagingOpenUntil").isNull());
        assertEquals(1, after.get("items").size());
    }

    @Test
    void appointmentLinkOpensThreadUntilScheduledTime() throws Exception {
        long p = newPatient();
        newAppointment(p, "GENERAL_CONSULT", "VISCERAL_SURGERY", "2026-10-16T08:00:00");
        JsonNode t = thread(p, "");
        assertTrue(t.get("messagingOpen").asBoolean());
        assertEquals("2026-10-16T12:00:00Z", t.get("messagingOpenUntil").asText());
        assertEquals(201, send(p, "Hello").getResponse().getStatus());
        clock.set(java.time.Instant.parse("2026-10-16T13:00:00Z"));
        assertFalse(thread(p, "").get("messagingOpen").asBoolean());
    }

    @Test
    void unknownPatientAndNoLinkAtAll() throws Exception {
        assertEquals(404, send(987654321L, "x").getResponse().getStatus());
        assertEquals(404, call(get("/api/patients/987654321/messages")).getResponse().getStatus());
        long p = newPatient();
        assertEquals(409, send(p, "x").getResponse().getStatus());
        assertEquals(0, thread(p, "").get("items").size());
    }

    @Test
    void beforePagingReturnsAscendingPages() throws Exception {
        long p = newPatient();
        newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        long[] ids = new long[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = json(send(p, "m" + i)).get("id").asLong();
        }
        JsonNode newest = thread(p, "?limit=2");
        assertEquals(ids[3], newest.get("items").get(0).get("id").asLong());
        assertEquals(ids[4], newest.get("items").get(1).get("id").asLong());
        assertTrue(newest.get("hasMore").asBoolean());
        JsonNode older = thread(p, "?limit=2&before=" + ids[3]);
        assertEquals(ids[1], older.get("items").get(0).get("id").asLong());
        assertEquals(ids[2], older.get("items").get(1).get("id").asLong());
        assertTrue(older.get("hasMore").asBoolean());
        JsonNode oldest = thread(p, "?limit=2&before=" + ids[1]);
        assertEquals(1, oldest.get("items").size());
        assertFalse(oldest.get("hasMore").asBoolean());
        assertEquals(1, auditCount(p, "MESSAGES_VIEWED"), "only the first page (no before) is audited");
        assertEquals(400, call(get("/api/patients/" + p + "/messages?limit=0")).getResponse().getStatus());
        assertEquals(400, call(get("/api/patients/" + p + "/messages?limit=101")).getResponse().getStatus());
    }

    @Test
    void markReadSetsOnlyUpToId() throws Exception {
        long p = newPatient();
        String code = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null).get("accessCode").asText();
        long m1 = json(patientSend(code, "one")).get("id").asLong();
        long m2 = json(patientSend(code, "two")).get("id").asLong();
        long m3 = json(patientSend(code, "three")).get("id").asLong();
        long before = unread();
        MvcResult r = call(post("/api/patients/" + p + "/messages/read").contentType(MediaType.APPLICATION_JSON).content("{\"upToId\":" + m2 + "}"));
        assertEquals(204, r.getResponse().getStatus());
        assertEquals(before - 2, unread());
        JsonNode items = thread(p, "").get("items");
        assertFalse(items.get(0).get("readByStaffAt").isNull());
        assertFalse(items.get(1).get("readByStaffAt").isNull());
        assertTrue(items.get(2).get("readByStaffAt").isNull());
        assertEquals(m3, items.get(2).get("id").asLong());
        assertEquals(m1, items.get(0).get("id").asLong());
        assertEquals(400, call(post("/api/patients/" + p + "/messages/read").contentType(MediaType.APPLICATION_JSON).content("{\"upToId\":0}")).getResponse().getStatus());
        assertEquals(400, call(post("/api/patients/" + p + "/messages/read").contentType(MediaType.APPLICATION_JSON).content("{}")).getResponse().getStatus());
    }

    @Test
    void inboxOrderPreviewUnreadAndOpenState() throws Exception {
        long a = newPatient();
        long b = newPatient();
        String codeA = newFollowUp(a, "VISCERAL_SURGERY", "2026-10-13", null).get("accessCode").asText();
        String codeB = newFollowUp(b, "VISCERAL_SURGERY", "2026-10-13", null).get("accessCode").asText();
        long baseline = unread();
        patientSend(codeA, "x".repeat(200));
        clock.plus(Duration.ofMinutes(5));
        patientSend(codeB, "short");
        patientSend(codeB, "short two");
        assertEquals(baseline + 3, unread());

        JsonNode inbox = json(call(get("/api/messages/inbox")));
        int ia = -1;
        int ib = -1;
        for (int i = 0; i < inbox.size(); i++) {
            if (inbox.get(i).get("patientId").asLong() == a) ia = i;
            if (inbox.get(i).get("patientId").asLong() == b) ib = i;
        }
        assertTrue(ib >= 0 && ia >= 0 && ib < ia, "newest conversation first");
        JsonNode ca = inbox.get(ia);
        assertEquals("x".repeat(140) + "…", ca.get("lastMessagePreview").asText());
        assertEquals(1, ca.get("unreadCount").asInt());
        assertEquals("PATIENT", ca.get("lastSender").asText());
        assertTrue(ca.get("messagingOpen").asBoolean());
        assertEquals(2, inbox.get(ib).get("unreadCount").asInt());
        assertEquals("short two", inbox.get(ib).get("lastMessagePreview").asText());
        assertEquals("Test", ca.get("patientFirstName").asText());
        assertTrue(inbox.size() <= 200);
        JsonNode counts = json(call(get("/api/messages/unread-count")));
        assertTrue(counts.get("conversations").asLong() >= 2);
    }

    @Test
    void bodyRules() throws Exception {
        long p = newPatient();
        newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        MvcResult ctl = send(p, "bad\u0007body");
        assertEquals(400, ctl.getResponse().getStatus());
        assertEquals("must be plain text", json(ctl).get("fieldErrors").get("body").asText());
        assertEquals(400, send(p, "a".repeat(2001)).getResponse().getStatus());
        assertEquals(400, send(p, "   ").getResponse().getStatus());
        assertEquals(201, send(p, "a".repeat(2000)).getResponse().getStatus());
        MvcResult crlf = send(p, "  line one\r\nline two\rline three  ");
        assertEquals("line one\nline two\nline three", json(crlf).get("body").asText());
        assertEquals("kept", json(send(p, "\t kept")).get("body").asText());
    }
}
