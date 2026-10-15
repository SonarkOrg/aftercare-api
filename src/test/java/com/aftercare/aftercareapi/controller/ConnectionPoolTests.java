package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * With a single pooled connection, a read-only request that needed a second connection for its audit row
 * (REQUIRES_NEW nested inside the read transaction) would time out. The view audits now run after the read commits.
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=2000"})
class ConnectionPoolTests extends IntegrationTest {

    @Test
    void auditedViewsSucceedWithAPoolOfOneAndStillAudit() throws Exception {
        long p = newPatient();
        assertEquals(200, call(put("/api/patients/" + p + "/health-number").contentType(MediaType.APPLICATION_JSON)
                .content("{\"healthNumber\":\"TREM 6854 1212\"}")).getResponse().getStatus());
        MvcResult doc = call(multipart("/api/patients/" + p + "/documents", pdfFile("a.pdf"), "OTHER", null));
        assertEquals(201, doc.getResponse().getStatus(), doc.getResponse().getContentAsString());
        long docId = json(doc).get("id").asLong();

        assertEquals(200, call(get("/api/patients/" + p + "/medical")).getResponse().getStatus());
        MvcResult reveal = call(post("/api/patients/" + p + "/health-number/reveal").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Billing check\"}"));
        assertEquals(200, reveal.getResponse().getStatus(), reveal.getResponse().getContentAsString());
        assertEquals(200, call(get("/api/documents/" + docId + "/content")).getResponse().getStatus());
        assertEquals(200, call(get("/api/patients/" + p + "/messages")).getResponse().getStatus());

        assertEquals(1, auditCount(p, "PATIENT_FILE_VIEWED"));
        assertEquals(1, auditCount(p, "HEALTH_NUMBER_REVEALED"));
        assertEquals(1, auditCount(p, "DOCUMENT_DOWNLOADED"));
        assertEquals(1, auditCount(p, "MESSAGES_VIEWED"));
        assertEquals("reason: Billing check", jdbc.queryForObject(
                "select detail from audit_events where patient_id = ? and action = 'HEALTH_NUMBER_REVEALED'", String.class, p));
    }

    @Test
    void notFoundIsNeverAuditedAndLeavesNoConnectionBehind() throws Exception {
        long before = jdbc.queryForObject("select count(*) from audit_events", Long.class);
        assertEquals(404, call(get("/api/patients/987654321/medical")).getResponse().getStatus());
        assertEquals(404, call(get("/api/documents/987654321/content")).getResponse().getStatus());
        assertEquals(404, call(get("/api/patients/987654321/messages")).getResponse().getStatus());
        assertEquals(before, jdbc.queryForObject("select count(*) from audit_events", Long.class));
        // the single connection is still usable afterwards
        assertEquals(200, call(get("/api/patients/" + newPatient() + "/medical")).getResponse().getStatus());
    }
}
