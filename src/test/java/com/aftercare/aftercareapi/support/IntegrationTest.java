package com.aftercare.aftercareapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Shared context (fixed clock, mocked JwtDecoder) for all MockMvc integration tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    @MockBean
    protected JwtDecoder jwtDecoder;
    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper om;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected com.aftercare.aftercareapi.Services.ClinicSettingsService clinicSettingsService;

    /** Staff every test class can rely on. {@code test|unlinked} is deliberately never inserted. */
    public static final String SUB_LEGACY = "auth0|nurse-a";
    public static final String SUB_ADMIN = "test|admin";
    public static final String SUB_PHYSICIAN = "test|physician";
    public static final String SUB_NURSE = "test|nurse";
    public static final String SUB_RECEPTION = "test|reception";
    public static final String SUB_INACTIVE = "test|inactive";
    public static final String SUB_UNLINKED = "test|unlinked";
    protected static final RequestPostProcessor UNLINKED = staffAs(SUB_UNLINKED);

    @Autowired
    protected com.aftercare.aftercareapi.config.security.StaffAccessDeniedHandler accessDeniedHandler;

    @BeforeEach
    void resetClock() {
        clock.reset();
        accessDeniedHandler.resetThrottle(); // ACCESS_DENIED audit rows are throttled per (sub, path); the clock is frozen
    }

    /** Every test starts from the default notification settings (the settings row is shared by all tests of a context). */
    @BeforeEach
    void resetNotificationSettings() {
        // the singleton row, created with plain JDBC (the service would need a second connection, which a pool of one cannot give)
        jdbc.update("insert into clinic_settings (id, name, province, default_language, notify_high, notify_medium, version) "
                + "select 1, 'Aftercare clinic', 'QC', 'FR', true, true, 0 where not exists (select 1 from clinic_settings where id = 1)");
        // the test clock is reset before every test, so a lease taken "in the future" by an earlier test must not linger
        jdbc.update("delete from job_locks");
        jdbc.update("update clinic_settings set email_enabled = false, sms_enabled = false, checkin_reminder_enabled = false, "
                + "checkin_reminder_time = time '18:00:00', appointment_reminder_enabled = false, appointment_reminder_hours = 48, "
                + "missed_checkin_enabled = true, missed_checkin_cutoff = time '20:00:00', default_language = 'FR', "
                + "retention_closed_follow_up_months = null, retention_deleted_document_months = null, retention_message_months = null, "
                + "retention_notification_months = null, retention_audit_months = null, notifications_updated_at = null, "
                + "notifications_updated_by_sub = null, notifications_updated_by_name = null, retention_updated_at = null, "
                + "retention_updated_by_sub = null, retention_updated_by_name = null");
    }

    /** Idempotent: inserts the shared staff members with plain JDBC (the context, and its database, is cached across classes). */
    @BeforeEach
    void ensureTestStaff() {
        ensureStaff(SUB_LEGACY, "Nurse A", "ADMIN", true);
        ensureStaff(SUB_ADMIN, "Test Admin", "ADMIN", true);
        ensureStaff(SUB_PHYSICIAN, "Test Physician", "PHYSICIAN", true);
        ensureStaff(SUB_NURSE, "Test Nurse", "NURSE", true);
        ensureStaff(SUB_RECEPTION, "Test Reception", "RECEPTION", true);
        ensureStaff(SUB_INACTIVE, "Test Inactive", "NURSE", false);
    }

    protected void ensureStaff(String sub, String name, String accessRole, boolean active) {
        Integer n = jdbc.queryForObject("select count(*) from staff_members where auth0subject = ?", Integer.class, sub);
        if (n != null && n > 0) {
            return;
        }
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("insert into staff_members (display_name, role, access_role, auth0subject, active, created_at, created_by_sub, "
                + "updated_at, updated_by_sub, version) values (?, 'OTHER', ?, ?, ?, ?, 'test', ?, 'test', 0)",
                name, accessRole, sub, active, now, now);
    }

    /** An Instant from whatever the JDBC driver returned for a timestamp column. */
    protected static java.time.Instant instant(Object timestamp) {
        if (timestamp instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        if (timestamp instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        throw new IllegalArgumentException("not a timestamp: " + timestamp);
    }

    /** Inserts an outbox row directly (for list, retention and dispatcher tests). Returns its id. */
    protected long insertOutbox(long patientId, String template, String status, boolean automated, java.time.Instant createdAt,
                                Long followUpId, Long appointmentId, String dedupeKey) {
        java.sql.Timestamp at = java.sql.Timestamp.from(createdAt);
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("insert into notification_outbox (patient_id, follow_up_id, appointment_id, channel, "
                    + "template_key, language, status, automated, dedupe_key, attempts, next_attempt_at, created_at, "
                    + "created_by_sub, updated_at, version) values (?, ?, ?, 'EMAIL', ?, 'FR', ?, ?, ?, 0, ?, ?, 'test', ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, patientId);
            ps.setObject(2, followUpId);
            ps.setObject(3, appointmentId);
            ps.setString(4, template);
            ps.setString(5, status);
            ps.setBoolean(6, automated);
            ps.setString(7, dedupeKey);
            ps.setTimestamp(8, at);
            ps.setTimestamp(9, at);
            ps.setTimestamp(10, at);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Makes sure the clinic settings row exists, then sets the notification columns directly. */
    protected void settings(boolean email, boolean sms) throws Exception {
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/settings/clinic"));
        jdbc.update("update clinic_settings set email_enabled = ?, sms_enabled = ?", email, sms);
    }

    /** Records consent (and the preferred channel) through the real endpoint. */
    protected void consent(long patientId, String consent, String channel, String language) throws Exception {
        MvcResult r = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/patients/" + patientId + "/contact-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredChannel\":\"" + channel + "\",\"language\":" + (language == null ? "null" : "\"" + language + "\"")
                        + ",\"contactConsent\":\"" + consent + "\"}"));
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("contact preferences -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
    }

    /** A staff JWT for the seeded member of this access role. */
    protected static RequestPostProcessor asRole(com.aftercare.aftercareapi.Enums.AccessRole role) {
        return staffAs(switch (role) {
            case ADMIN -> SUB_ADMIN;
            case PHYSICIAN -> SUB_PHYSICIAN;
            case NURSE -> SUB_NURSE;
            case RECEPTION -> SUB_RECEPTION;
        });
    }

    protected static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b) {
        return b.with(jwt().jwt(j -> j.subject("auth0|nurse-a")));
    }

    /** A staff JWT with a custom subject and an optional {@code name} claim. */
    protected static RequestPostProcessor staffAs(String sub, String name) {
        return jwt().jwt(j -> {
            j.subject(sub);
            if (name != null) {
                j.claim("name", name);
            }
        });
    }

    protected static RequestPostProcessor staffAs(String sub) {
        return staffAs(sub, null);
    }

    protected MvcResult as(RequestPostProcessor who, MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(b.with(who)).andReturn();
    }

    protected static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates a staff member through the API and returns its id. */
    protected long newStaff(String displayName, String role, String email, String sub) throws Exception {
        return newStaff(displayName, role, "NURSE", email, sub);
    }

    protected long newStaff(String displayName, String role, String accessRole, String email, String sub) throws Exception {
        String body = om.writeValueAsString(java.util.Map.of("displayName", displayName, "role", role, "accessRole", accessRole,
                "email", email == null ? "" : email, "auth0Subject", sub == null ? "" : sub));
        MvcResult r = call(post("/api/staff").contentType(MediaType.APPLICATION_JSON).content(body));
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("create staff -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return json(r).get("id").asLong();
    }

    protected MockHttpServletRequestBuilder multipart(String url, MockMultipartFile file, String category, String title) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(url);
        if (file != null) {
            ((org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder) b).file(file);
        }
        if (category != null) {
            b.param("category", category);
        }
        if (title != null) {
            b.param("title", title);
        }
        return b;
    }

    protected List<Map<String, Object>> auditRows(long patientId) {
        return jdbc.queryForList("select id, action, actor_sub, actor_name, resource_type, resource_id, detail from audit_events where patient_id = ? order by id", patientId);
    }

    protected long auditCount(long patientId, String action) {
        return jdbc.queryForObject("select count(*) from audit_events where patient_id = ? and action = ?", Long.class, patientId, action);
    }

    protected static final String PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF";

    protected static MockMultipartFile pdfFile(String name) {
        return new MockMultipartFile("file", name, "text/plain", PDF.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    protected MvcResult call(MockHttpServletRequestBuilder b) throws Exception {
        return mockMvc.perform(staff(b)).andReturn();
    }

    protected JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    protected JsonNode postJson(String url, String body) throws Exception {
        MvcResult r = call(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
        return json(r);
    }

    protected long newPatient() throws Exception {
        String body = """
                {"firstName":"Test","lastName":"Patient","email":"t@example.com","phone":"514-555-0100",
                 "gender":"female","address":{"city":"Montreal","state":"QC","zip":"H2X 1Y4"}}""";
        return postJson("/api/patients/register", body).get("id").asLong();
    }

    /** Creates a follow-up (discharged yesterday by default) and returns the FollowUpWithCodeDTO. */
    protected JsonNode newFollowUp(long patientId, String specialty, String dischargeDate, Integer days) throws Exception {
        String body = "{\"specialty\":\"%s\",\"dischargeDate\":\"%s\"%s}".formatted(specialty, dischargeDate,
                days == null ? "" : ",\"durationDays\":" + days);
        MvcResult r = call(post("/api/patients/" + patientId + "/follow-ups").contentType(MediaType.APPLICATION_JSON).content(body));
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("create follow-up -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return json(r);
    }

    protected JsonNode newAppointment(long patientId, String type, String specialty, String scheduledAt) throws Exception {
        String body = "{\"patientId\":%d,\"type\":\"%s\",\"specialty\":\"%s\",\"scheduledAt\":\"%s\",\"durationMinutes\":30}"
                .formatted(patientId, type, specialty, scheduledAt);
        MvcResult r = call(post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content(body));
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("create appointment -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return json(r);
    }

    protected static final String VISCERAL_Q = "https://aftercare.example/fhir/Questionnaire/followup-visceral-surgery|2";

    protected static String checkInBody(int pain, Double temp, boolean redness) {
        return """
                {"resourceType":"QuestionnaireResponse","questionnaire":"%s","status":"completed","item":[
                 {"linkId":"PAIN","answer":[{"valueInteger":%d}]},
                 %s
                 {"linkId":"WOUND_REDNESS","answer":[{"valueBoolean":%s}]},
                 {"linkId":"WOUND_DISCHARGE","answer":[{"valueBoolean":false}]},
                 {"linkId":"NAUSEA_VOMITING","answer":[{"valueBoolean":false}]},
                 {"linkId":"ABLE_TO_EAT","answer":[{"valueBoolean":true}]},
                 {"linkId":"SHORTNESS_OF_BREATH","answer":[{"valueBoolean":false}]}]}"""
                .formatted(VISCERAL_Q, pain, temp == null ? "" : "{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":" + temp + "}]},", redness);
    }

    protected static String intakeBody(String qUrl, boolean surgery, boolean anticoagulant, boolean withName) {
        String common = """
                {"linkId":"WILL_ATTEND","answer":[{"valueBoolean":true}]},
                {"linkId":"TAKES_ANTICOAGULANT","answer":[{"valueBoolean":%s}]},
                %s
                {"linkId":"NEEDS_INTERPRETER","answer":[{"valueBoolean":false}]}"""
                .formatted(anticoagulant, withName ? "{\"linkId\":\"ANTICOAGULANT_NAME\",\"answer\":[{\"valueString\":\"Eliquis hier\"}]}," : "");
        String extra = surgery
                ? ",{\"linkId\":\"RECENT_ILLNESS\",\"answer\":[{\"valueBoolean\":false}]},{\"linkId\":\"FASTING_ACKNOWLEDGED\",\"answer\":[{\"valueBoolean\":true}]},"
                  + "{\"linkId\":\"RIDE_HOME\",\"answer\":[{\"valueBoolean\":true}]},{\"linkId\":\"CONSENT_READ\",\"answer\":[{\"valueBoolean\":true}]}"
                : ",{\"linkId\":\"REASON_FOR_VISIT\",\"answer\":[{\"valueString\":\"Douleur\"}]}";
        return "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"" + qUrl + "|1\",\"status\":\"completed\",\"item\":["
                + common + extra + "]}";
    }

    protected static final String SURGERY_URL = "https://aftercare.example/fhir/Questionnaire/pre-surgery";
    protected static final String CONSULT_URL = "https://aftercare.example/fhir/Questionnaire/pre-consult";
}
