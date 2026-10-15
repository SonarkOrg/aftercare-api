package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class DocumentControllerTests extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private MvcResult upload(long p, String name, String clientType, byte[] bytes, String category, String title) throws Exception {
        return call(multipart("/api/patients/" + p + "/documents", new MockMultipartFile("file", name, clientType, bytes), category, title));
    }

    @Test
    void pdfIsSniffedNotTrusted() throws Exception {
        long p = newPatient();
        MvcResult r = call(multipart("/api/patients/" + p + "/documents", pdfFile("..\\evil/path/report.final.pdf"), "LAB_RESULT", null));
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode d = json(r);
        assertEquals("application/pdf", d.get("contentType").asText());
        assertEquals("report.final.pdf", d.get("originalFilename").asText());
        assertEquals("report.final", d.get("title").asText());
        assertEquals(64, d.get("sha256").asText().length());
        assertEquals(PDF.length(), d.get("sizeBytes").asLong());
        assertEquals(1, auditCount(p, "DOCUMENT_UPLOADED"));
    }

    @Test
    void renamedPngIsStoredAsPng() throws Exception {
        long p = newPatient();
        MvcResult r = upload(p, "scan.pdf", "application/pdf", PNG, "IMAGING", "Scan");
        assertEquals(201, r.getResponse().getStatus());
        assertEquals("image/png", json(r).get("contentType").asText());
        MvcResult c = call(get("/api/documents/" + json(r).get("id").asLong() + "/content"));
        assertEquals("attachment; filename=\"document-" + json(r).get("id").asLong() + ".png\"", c.getResponse().getHeader("Content-Disposition"));
    }

    @Test
    void unsupportedAndInvalidUploads() throws Exception {
        long p = newPatient();
        MvcResult html = upload(p, "a.pdf", "application/pdf", "<html><script>x</script></html>".getBytes(StandardCharsets.UTF_8), "OTHER", null);
        assertEquals(415, html.getResponse().getStatus());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", json(html).get("error").asText());
        assertEquals(415, upload(p, "a.gif", "image/gif", "GIF89a....".getBytes(StandardCharsets.US_ASCII), "OTHER", null).getResponse().getStatus());
        MvcResult empty = upload(p, "a.pdf", "application/pdf", new byte[0], "OTHER", null);
        assertEquals(400, empty.getResponse().getStatus());
        assertEquals("must not be empty", json(empty).get("fieldErrors").get("file").asText());
        MvcResult noFile = call(multipart("/api/patients/" + p + "/documents", null, "OTHER", null).contentType(MediaType.MULTIPART_FORM_DATA));
        assertEquals(400, noFile.getResponse().getStatus());
        assertEquals("required", json(noFile).get("fieldErrors").get("file").asText());
        MvcResult badCat = call(multipart("/api/patients/" + p + "/documents", pdfFile("a.pdf"), "NOPE", null));
        assertEquals(400, badCat.getResponse().getStatus());
        assertEquals("invalid value", json(badCat).get("fieldErrors").get("category").asText());
        assertEquals(404, call(multipart("/api/patients/987654321/documents", pdfFile("a.pdf"), "OTHER", null)).getResponse().getStatus());
    }

    /**
     * Exercises the service-level size check only. MockMvc bypasses Tomcat, so the container's multipart limit
     * (spring.servlet.multipart.max-file-size) is not covered here.
     */
    @Test
    void oversizeUploadIs413FromServiceLevelCheck() throws Exception {
        long p = newPatient();
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 8);
        MvcResult r = upload(p, "big.pdf", "application/pdf", big, "OTHER", null);
        assertEquals(413, r.getResponse().getStatus());
        JsonNode e = json(r);
        assertEquals("PAYLOAD_TOO_LARGE", e.get("error").asText());
        assertEquals("File exceeds 10 MB", e.get("message").asText());
        assertEquals(413, e.get("status").asInt());
    }

    @Test
    void contentHeadersAndBytesAreExact() throws Exception {
        long p = newPatient();
        byte[] bytes = (PDF + "\n" + "x".repeat(2000)).getBytes(StandardCharsets.ISO_8859_1);
        long id = json(upload(p, "a.pdf", "application/octet-stream", bytes, "DISCHARGE_SUMMARY", "Summary")).get("id").asLong();
        MvcResult c = call(get("/api/documents/" + id + "/content"));
        assertEquals(200, c.getResponse().getStatus());
        assertTrue(Arrays.equals(bytes, c.getResponse().getContentAsByteArray()));
        var h = c.getResponse();
        assertEquals("application/pdf", h.getHeader("Content-Type"));
        assertEquals(String.valueOf(bytes.length), h.getHeader("Content-Length"));
        assertEquals("attachment; filename=\"document-" + id + ".pdf\"", h.getHeader("Content-Disposition"));
        assertEquals(1, h.getHeaders("X-Content-Type-Options").size());
        assertEquals("nosniff", h.getHeader("X-Content-Type-Options"));
        assertEquals("no-store", h.getHeader("Cache-Control"));
        assertEquals("default-src 'none'; sandbox", h.getHeader("Content-Security-Policy"));
        assertEquals(1, auditCount(p, "DOCUMENT_DOWNLOADED"));
        assertEquals(404, call(get("/api/documents/987654321/content")).getResponse().getStatus());
    }

    @Test
    void deleteIsSoft() throws Exception {
        long p = newPatient();
        long id = json(call(multipart("/api/patients/" + p + "/documents", pdfFile("a.pdf"), "CONSENT", "Consent"))).get("id").asLong();
        assertEquals(1, json(call(get("/api/patients/" + p + "/documents"))).size());
        assertEquals(400, call(post("/api/documents/" + id + "/delete").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).getResponse().getStatus());
        assertEquals(204, call(post("/api/documents/" + id + "/delete").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"uploaded twice\"}")).getResponse().getStatus());
        assertEquals(0, json(call(get("/api/patients/" + p + "/documents"))).size());
        assertEquals(404, call(get("/api/documents/" + id + "/content")).getResponse().getStatus());
        assertEquals(404, call(post("/api/documents/" + id + "/delete").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again again\"}")).getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from document_blobs where id = (select storage_key from patient_documents where id = ?)", Long.class, id));
        assertEquals("reason: uploaded twice", auditRows(p).stream().filter(r -> "DOCUMENT_DELETED".equals(r.get("action"))).findFirst().orElseThrow().get("detail"));
    }

    @Test
    void listIsNewestFirst() throws Exception {
        long p = newPatient();
        long first = json(call(multipart("/api/patients/" + p + "/documents", pdfFile("one.pdf"), "OTHER", null))).get("id").asLong();
        clock.plus(java.time.Duration.ofMinutes(5));
        long second = json(call(multipart("/api/patients/" + p + "/documents", pdfFile("two.pdf"), "OTHER", null))).get("id").asLong();
        JsonNode list = json(call(get("/api/patients/" + p + "/documents")));
        assertEquals(second, list.get(0).get("id").asLong());
        assertEquals(first, list.get(1).get("id").asLong());
        assertEquals(404, call(get("/api/patients/987654321/documents")).getResponse().getStatus());
    }
}
