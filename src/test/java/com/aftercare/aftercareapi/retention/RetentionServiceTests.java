package com.aftercare.aftercareapi.retention;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.Enums.RetentionMode;
import com.aftercare.aftercareapi.Enums.RetentionTrigger;
import com.aftercare.aftercareapi.Models.Dto.RetentionCounts;
import com.aftercare.aftercareapi.Models.Dto.RetentionRunDTO;
import com.aftercare.aftercareapi.Services.Actor;
import com.aftercare.aftercareapi.Services.jobs.JobLockService;
import com.aftercare.aftercareapi.Services.jobs.RetentionJob;
import com.aftercare.aftercareapi.Services.jobs.RetentionService;
import com.aftercare.aftercareapi.config.RetentionProperties;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Own context (and database): the fixtures include rows far in the past that other tests must not see. */
@TestPropertySource(properties = "aftercare.retention.purge.batch-size=10")
class RetentionServiceTests extends IntegrationTest {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final Actor ADMIN = new Actor(SUB_ADMIN, "Test Admin");

    @Autowired RetentionService retention;
    @Autowired RetentionJob job;
    @Autowired JobLockService locks;

    @BeforeEach
    void emptyOutData() {
        for (String table : List.of("retention_runs", "patient_activity", "alerts", "check_ins", "patient_access_links", "notification_outbox",
                "follow_ups", "patient_documents", "document_blobs", "patient_messages", "audit_events")) {
            jdbc.update("delete from " + table);
        }
    }

    private void on(LocalDate day) {
        clock.set(day.atTime(12, 0).atZone(TORONTO).toInstant());
    }

    private static Instant ts(String iso) {
        return Instant.parse(iso);
    }

    private void periods(Integer followUps, Integer documents, Integer messages, Integer notifications, Integer audit) {
        jdbc.update("update clinic_settings set retention_closed_follow_up_months = ?, retention_deleted_document_months = ?, "
                        + "retention_message_months = ?, retention_notification_months = ?, retention_audit_months = ?",
                followUps, documents, messages, notifications, audit);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private record Fixture(long oldClosed, long oldExpired, long recentClosed, long oldClosedCheckIn, List<Long> oldAlerts,
                           long oldDeletedDoc, long oldActiveDoc, long recentDeletedDoc, String oldDeletedKey, String oldActiveKey,
                           long retainedPatient) {
    }

    /** Old and recent data of every category. "Now" is 2026-10-14; twelve months back is 2025-10-14. */
    private Fixture build() throws Exception {
        // an old closed follow-up with a check-in that raised alerts, a link, an outbox row and its activity
        on(LocalDate.of(2024, 1, 10));
        long p1 = newPatient();
        JsonNode f1 = newFollowUp(p1, "VISCERAL_SURGERY", "2024-01-09", 7);
        long oldClosed = f1.get("followUp").get("id").asLong();
        mockMvc.perform(post("/api/public/q/" + f1.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(8, 38.4, true))).andExpect(status().isCreated());
        on(LocalDate.of(2024, 1, 12));
        assertEquals(200, call(post("/api/follow-ups/" + oldClosed + "/close")).getResponse().getStatus());
        long checkIn = count("select id from check_ins where follow_up_id = ?", oldClosed);
        List<Long> alerts = jdbc.queryForList("select id from alerts where follow_up_id = ? order by id", Long.class, oldClosed);
        insertOutbox(p1, "FOLLOW_UP_LINK", "PENDING", true, ts("2024-01-10T15:00:00Z"), oldClosed, null, null);

        // an old follow-up that was never closed but expired long ago
        on(LocalDate.of(2024, 1, 10));
        long p2 = newPatient();
        long oldExpired = newFollowUp(p2, "ORTHOPEDIC_SURGERY", "2024-01-09", 7).get("followUp").get("id").asLong();

        // a recently closed one stays
        on(LocalDate.of(2026, 10, 1));
        long p3 = newPatient();
        JsonNode f3 = newFollowUp(p3, "VISCERAL_SURGERY", "2026-09-30", 7);
        long recentClosed = f3.get("followUp").get("id").asLong();
        mockMvc.perform(post("/api/public/q/" + f3.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(8, 38.4, true))).andExpect(status().isCreated());
        assertEquals(200, call(post("/api/follow-ups/" + recentClosed + "/close")).getResponse().getStatus());

        // documents: one deleted long ago, one never deleted (old), one deleted recently
        on(LocalDate.of(2024, 2, 1));
        long docPatient = newPatient();
        long oldDeleted = json(call(multipart("/api/patients/" + docPatient + "/documents", pdfFile("a.pdf"), "OTHER", "old deleted"))).get("id").asLong();
        long oldActive = json(call(multipart("/api/patients/" + docPatient + "/documents", pdfFile("b.pdf"), "OTHER", "old active"))).get("id").asLong();
        long recentDeleted = json(call(multipart("/api/patients/" + docPatient + "/documents", pdfFile("c.pdf"), "OTHER", "recent"))).get("id").asLong();
        on(LocalDate.of(2024, 3, 1));
        assertEquals(204, call(post("/api/documents/" + oldDeleted + "/delete").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"entered in error\"}")).getResponse().getStatus());
        on(LocalDate.of(2026, 9, 20));
        assertEquals(204, call(post("/api/documents/" + recentDeleted + "/delete").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"entered in error\"}")).getResponse().getStatus());
        String oldDeletedKey = jdbc.queryForObject("select storage_key from patient_documents where id = ?", String.class, oldDeleted);
        String oldActiveKey = jdbc.queryForObject("select storage_key from patient_documents where id = ?", String.class, oldActive);

        // messages: two old, one recent
        for (String at : List.of("2024-05-01T12:00:00Z", "2025-01-01T12:00:00Z", "2026-10-01T12:00:00Z")) {
            jdbc.update("insert into patient_messages (patient_id, sender, body, created_at) values (?, 'PATIENT', 'hello', ?)", docPatient, Timestamp.from(ts(at)));
        }
        // outbox: old terminal rows go, an old PENDING row and recent rows stay
        insertOutbox(docPatient, "CHECKIN_REMINDER", "SENT", true, ts("2024-06-01T12:00:00Z"), null, null, null);
        insertOutbox(docPatient, "CHECKIN_REMINDER", "FAILED", true, ts("2024-06-02T12:00:00Z"), null, null, null);
        insertOutbox(docPatient, "APPOINTMENT_LINK", "CANCELLED", false, ts("2024-06-03T12:00:00Z"), null, null, null);
        insertOutbox(docPatient, "APPOINTMENT_LINK", "SKIPPED", false, ts("2025-01-03T12:00:00Z"), null, null, null);
        insertOutbox(docPatient, "APPOINTMENT_LINK", "PENDING", false, ts("2024-06-04T12:00:00Z"), null, null, null);
        insertOutbox(docPatient, "APPOINTMENT_LINK", "SENT", false, ts("2026-10-01T12:00:00Z"), null, null, null);
        // old audit rows
        for (int i = 0; i < 3; i++) {
            jdbc.update("insert into audit_events (occurred_at, actor_sub, action, resource_type) values (?, 'old', 'PATIENT_UPDATED', 'PATIENT')",
                    Timestamp.from(ts("2024-0" + (i + 1) + "-15T12:00:00Z")));
        }
        on(LocalDate.of(2026, 10, 14));
        return new Fixture(oldClosed, oldExpired, recentClosed, checkIn, alerts, oldDeleted, oldActive, recentDeleted, oldDeletedKey,
                oldActiveKey, p3);
    }

    private long oldAudit() {
        return count("select count(*) from audit_events where occurred_at < ?", Timestamp.from(ts("2025-10-14T16:00:00Z")));
    }

    @Test
    void allNullPeriodsCountAndDeleteNothing() throws Exception {
        build();
        long followUps = count("select count(*) from follow_ups");
        long audit = count("select count(*) from audit_events");
        RetentionRunDTO dry = retention.run(RetentionMode.DRY_RUN, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(RetentionCounts.ZERO, dry.counts());
        RetentionRunDTO purge = retention.run(RetentionMode.PURGE, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(RetentionCounts.ZERO, purge.counts());
        assertEquals(followUps, count("select count(*) from follow_ups"));
        assertTrue(count("select count(*) from audit_events") >= audit, "only the two run audits were added");
    }

    @Test
    void aDryRunCountsTheFixturesAndDeletesNothing() throws Exception {
        Fixture f = build();
        periods(12, 12, 12, 12, null);
        long before = count("select count(*) from patient_activity") + count("select count(*) from check_ins") + count("select count(*) from alerts")
                + count("select count(*) from follow_ups") + count("select count(*) from patient_documents")
                + count("select count(*) from document_blobs") + count("select count(*) from patient_messages")
                + count("select count(*) from notification_outbox") + count("select count(*) from patient_access_links");
        RetentionRunDTO dry = retention.run(RetentionMode.DRY_RUN, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(RetentionMode.DRY_RUN, dry.mode());
        assertEquals(RetentionTrigger.MANUAL, dry.trigger());
        assertEquals("SUCCEEDED", dry.status().name());
        assertEquals(new RetentionCounts(2, 1, f.oldAlerts().size(), 1, 2, 4, 0), dry.counts());
        assertTrue(f.oldAlerts().size() >= 2);
        long after = count("select count(*) from patient_activity") + count("select count(*) from check_ins") + count("select count(*) from alerts")
                + count("select count(*) from follow_ups") + count("select count(*) from patient_documents")
                + count("select count(*) from document_blobs") + count("select count(*) from patient_messages")
                + count("select count(*) from notification_outbox") + count("select count(*) from patient_access_links");
        assertEquals(before, after);
    }

    @Test
    void aPurgeDeletesExactlyTheExpiredRowsAndTheirActivity() throws Exception {
        Fixture f = build();
        periods(12, 12, 12, 12, null);
        long auditBefore = count("select count(*) from audit_events");
        long recentActivity = count("select count(*) from patient_activity where patient_id = ?", f.retainedPatient());
        RetentionRunDTO run = retention.run(RetentionMode.PURGE, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(new RetentionCounts(2, 1, f.oldAlerts().size(), 1, 2, 4, 0), run.counts());
        assertEquals("SUCCEEDED", run.status().name());

        // follow-ups: the two old ones and everything under them are gone, the recent one is intact
        assertEquals(0, count("select count(*) from follow_ups where id in (?, ?)", f.oldClosed(), f.oldExpired()));
        assertEquals(1, count("select count(*) from follow_ups where id = ?", f.recentClosed()));
        assertEquals(0, count("select count(*) from check_ins where follow_up_id = ?", f.oldClosed()));
        assertEquals(0, count("select count(*) from alerts where follow_up_id = ?", f.oldClosed()));
        assertEquals(0, count("select count(*) from patient_access_links where follow_up_id in (?, ?)", f.oldClosed(), f.oldExpired()));
        assertEquals(0, count("select count(*) from notification_outbox where follow_up_id = ?", f.oldClosed()));
        assertEquals(0, count("select count(*) from patient_activity where kind = 'CHECK_IN_SUBMITTED' and ref_id = ?", f.oldClosedCheckIn()));
        for (long alert : f.oldAlerts()) {
            assertEquals(0, count("select count(*) from patient_activity where kind like 'ALERT_%' and ref_id = ?", alert));
        }
        assertEquals(0, count("select count(*) from patient_activity where kind in ('FOLLOW_UP_STARTED','FOLLOW_UP_CLOSED') and ref_id in (?, ?)", f.oldClosed(), f.oldExpired()));
        assertEquals(1, count("select count(*) from check_ins where follow_up_id = ?", f.recentClosed()));
        assertTrue(count("select count(*) from alerts where follow_up_id = ?", f.recentClosed()) > 0);
        assertEquals(recentActivity, count("select count(*) from patient_activity where patient_id = ?", f.retainedPatient()));
        assertEquals(1, count("select count(*) from patient_access_links where follow_up_id = ?", f.recentClosed()));

        // documents: only the soft-deleted old one, including its bytes and activity
        assertEquals(0, count("select count(*) from patient_documents where id = ?", f.oldDeletedDoc()));
        assertEquals(0, count("select count(*) from document_blobs where id = ?", f.oldDeletedKey()));
        assertEquals(0, count("select count(*) from patient_activity where kind like 'DOCUMENT_%' and ref_id = ?", f.oldDeletedDoc()));
        assertEquals(1, count("select count(*) from patient_documents where id = ?", f.oldActiveDoc()));
        assertEquals(1, count("select count(*) from document_blobs where id = ?", f.oldActiveKey()));
        assertTrue(count("select count(*) from patient_activity where kind like 'DOCUMENT_%' and ref_id = ?", f.oldActiveDoc()) > 0);
        assertEquals(1, count("select count(*) from patient_documents where id = ?", f.recentDeletedDoc()));

        // messages and outbox
        assertEquals(1, count("select count(*) from patient_messages where body = 'hello'"));
        assertEquals(1, count("select count(*) from patient_messages where created_at > ?", Timestamp.from(ts("2026-01-01T00:00:00Z"))));
        assertEquals(List.of("PENDING", "SENT"), jdbc.queryForList("select status from notification_outbox where follow_up_id is null order by status", String.class));

        // audit rows are only deleted by their own period (none here), plus the run's own row
        assertEquals(auditBefore + 1, count("select count(*) from audit_events"));
        assertTrue(oldAudit() >= 3);
    }

    @Test
    void auditMonthsDeletesOnlyOlderAuditRowsAndKeepsTheRunsOwnRow() throws Exception {
        build();
        periods(null, null, null, null, 12);
        long old = oldAudit();
        assertTrue(old >= 3);
        long total = count("select count(*) from audit_events");
        RetentionRunDTO dry = retention.run(RetentionMode.DRY_RUN, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(old, dry.counts().auditEvents());
        assertEquals(0, dry.counts().followUps());
        RetentionRunDTO run = retention.run(RetentionMode.PURGE, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(old, run.counts().auditEvents());
        assertEquals(0, oldAudit());
        // rows from the last twelve months survive, and so do both runs' audit rows
        assertEquals(total - old + 2, count("select count(*) from audit_events"));
        assertEquals(1, count("select count(*) from audit_events where action = 'RETENTION_PURGE'"));
        assertEquals(1, count("select count(*) from audit_events where action = 'RETENTION_DRY_RUN'"));
        // nothing else was touched
        assertEquals(3, count("select count(*) from follow_ups"));
    }

    @Test
    void everyRunIsRecordedAndAuditedWithCountsOnly() throws Exception {
        Fixture f = build();
        periods(12, 12, 12, 12, null);
        RetentionRunDTO run = retention.run(RetentionMode.PURGE, RetentionTrigger.MANUAL, ADMIN);
        var row = jdbc.queryForMap("select mode, run_trigger, status, started_at, finished_at, follow_ups, check_ins, alerts, documents, messages, "
                + "notifications, audit_events, actor_sub, actor_name from retention_runs where id = ?", run.id());
        assertEquals("PURGE", row.get("mode"));
        assertEquals("MANUAL", row.get("run_trigger"));
        assertEquals("SUCCEEDED", row.get("status"));
        assertNotNull(row.get("finished_at"));
        assertEquals(2L, ((Number) row.get("follow_ups")).longValue());
        assertEquals(SUB_ADMIN, row.get("actor_sub"));
        assertEquals("Test Admin", row.get("actor_name"));
        var audit = jdbc.queryForMap("select actor_sub, actor_name, resource_type, patient_id, detail from audit_events where action = 'RETENTION_PURGE'");
        assertEquals("RETENTION", audit.get("resource_type"));
        assertNull(audit.get("patient_id"));
        assertEquals("followUps=2,checkIns=1,alerts=" + f.oldAlerts().size() + ",documents=1,messages=2,notifications=4,auditEvents=0", audit.get("detail"));
    }

    @Test
    void theDryRunEndpointAndTheRunsList() throws Exception {
        periods(12, null, null, null, null);
        MvcResult r = as(asRole(AccessRole.ADMIN), post("/api/retention/dry-run"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        JsonNode run = json(r);
        assertEquals("DRY_RUN", run.get("mode").asText());
        assertEquals("MANUAL", run.get("trigger").asText());
        assertEquals("SUCCEEDED", run.get("status").asText());
        assertEquals("Test Admin", run.get("actorName").asText());
        assertFalse(run.get("startedAt").isNull());
        assertFalse(run.get("finishedAt").isNull());
        assertEquals(List.of("followUps", "checkIns", "alerts", "documents", "messages", "notifications", "auditEvents"), fieldNames(run.get("counts")));
        assertEquals(List.of("id", "mode", "trigger", "status", "startedAt", "finishedAt", "counts", "actorName"), fieldNames(run));
        // it always works, whatever the purge flags say, and is listed newest first, last 20
        for (int i = 0; i < 22; i++) {
            clock.plus(Duration.ofMinutes(1));
            assertEquals(200, as(asRole(AccessRole.ADMIN), post("/api/retention/dry-run")).getResponse().getStatus());
        }
        JsonNode runs = json(as(asRole(AccessRole.ADMIN), get("/api/retention/runs")));
        assertEquals(20, runs.size());
        assertTrue(runs.get(0).get("startedAt").asText().compareTo(runs.get(1).get("startedAt").asText()) > 0);
        assertEquals(403, as(asRole(AccessRole.NURSE), post("/api/retention/dry-run")).getResponse().getStatus());
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> out = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void theScheduledJobDoesNothingUntilEnabledAndDryRunsByDefault() throws Exception {
        periods(12, 12, 12, 12, null);
        // the shipped defaults: disabled
        job.runOnce();
        assertEquals(0, count("select count(*) from retention_runs"));
        assertEquals(0, count("select count(*) from audit_events where action like 'RETENTION_%' and action <> 'RETENTION_SETTINGS_UPDATED'"));
        // enabled + dry-run
        new RetentionJob(retention, new RetentionProperties(new RetentionProperties.Purge(true, true, "0 15 3 * * *", 500))).runOnce();
        var runs = jdbc.queryForList("select mode, run_trigger, actor_sub, actor_name from retention_runs");
        assertEquals(1, runs.size());
        assertEquals("DRY_RUN", runs.get(0).get("mode"));
        assertEquals("SCHEDULED", runs.get(0).get("run_trigger"));
        assertEquals("system:retention", runs.get(0).get("actor_sub"));
        assertNull(runs.get(0).get("actor_name"));
        assertEquals(1, count("select count(*) from audit_events where action = 'RETENTION_DRY_RUN' and actor_sub = 'system:retention'"));
        // enabled + real purge
        clock.plus(Duration.ofMinutes(1));
        new RetentionJob(retention, new RetentionProperties(new RetentionProperties.Purge(true, false, "0 15 3 * * *", 500))).runOnce();
        assertEquals(1, count("select count(*) from retention_runs where mode = 'PURGE' and run_trigger = 'SCHEDULED'"));
    }

    @Test
    void aConcurrentRunIsRefusedManuallyAndSkippedWhenScheduled() throws Exception {
        assertTrue(locks.tryAcquire("retention", Duration.ofHours(1)));
        MvcResult r = as(asRole(AccessRole.ADMIN), post("/api/retention/dry-run"));
        assertEquals(409, r.getResponse().getStatus());
        assertEquals("INVALID_STATE", json(r).get("error").asText());
        assertEquals("A retention run is already in progress", json(r).get("message").asText());
        assertNull(retention.run(RetentionMode.DRY_RUN, RetentionTrigger.SCHEDULED, RetentionService.SYSTEM));
        assertEquals(0, count("select count(*) from retention_runs"));
        locks.release("retention");
        assertEquals(200, as(asRole(AccessRole.ADMIN), post("/api/retention/dry-run")).getResponse().getStatus());
        // the lock is released after each run
        assertEquals(200, as(asRole(AccessRole.ADMIN), post("/api/retention/dry-run")).getResponse().getStatus());
    }

    @Test
    void largeSetsAreDeletedInBatches() throws Exception {
        // batch-size is 10 in this class: 25 rows take three transactions
        long p = newPatient();
        for (int i = 0; i < 25; i++) {
            jdbc.update("insert into patient_messages (patient_id, sender, body, created_at) values (?, 'PATIENT', 'old', ?)", p, Timestamp.from(ts("2020-01-01T12:00:00Z")));
        }
        periods(null, null, 12, null, null);
        RetentionRunDTO run = retention.run(RetentionMode.PURGE, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(25, run.counts().messages());
        assertEquals(0, count("select count(*) from patient_messages where body = 'old'"));
    }


    @Test
    void messagePreviewsInThePatientTimelineArePurgedWithTheirMessages() throws Exception {
        on(LocalDate.of(2026, 10, 14));
        long p = newPatient();
        long oldIn = insertMessage(p, "PATIENT", "2020-01-01T12:00:00Z");
        long oldOut = insertMessage(p, "STAFF", "2020-01-02T12:00:00Z");
        long recent = insertMessage(p, "PATIENT", "2026-10-01T12:00:00Z");
        activityFor(p, "MESSAGE_FROM_PATIENT", oldIn, "2020-01-01T12:00:00Z");
        activityFor(p, "MESSAGE_TO_PATIENT", oldOut, "2020-01-02T12:00:00Z");
        activityFor(p, "MESSAGE_FROM_PATIENT", recent, "2026-10-01T12:00:00Z");
        // an activity row of another kind that happens to share a ref id must stay
        activityFor(p, "NOTE_ADDED", oldIn, "2020-01-01T12:00:00Z");
        periods(null, null, 12, null, null);

        RetentionRunDTO run = retention.run(RetentionMode.PURGE, RetentionTrigger.MANUAL, ADMIN);
        assertEquals(2, run.counts().messages());
        assertEquals(1, count("select count(*) from patient_messages"));
        assertEquals(0, count("select count(*) from patient_activity where kind in ('MESSAGE_FROM_PATIENT','MESSAGE_TO_PATIENT') and ref_id in (?, ?)", oldIn, oldOut));
        assertEquals(1, count("select count(*) from patient_activity where kind in ('MESSAGE_FROM_PATIENT','MESSAGE_TO_PATIENT') and ref_id = ?", recent));
        assertEquals(1, count("select count(*) from patient_activity where kind = 'NOTE_ADDED'"));
    }

    private long insertMessage(long patient, String sender, String at) {
        jdbc.update("insert into patient_messages (patient_id, sender, body, created_at) values (?, ?, 'secret preview', ?)", patient, sender, Timestamp.from(ts(at)));
        return count("select max(id) from patient_messages");
    }

    private void activityFor(long patient, String kind, long ref, String at) {
        String category = kind.startsWith("MESSAGE") ? "MESSAGE" : "NOTE";
        jdbc.update("insert into patient_activity (occurred_at, patient_id, ref_id, actor_type, category, kind, summary) values (?, ?, ?, 'PATIENT', ?, ?, 'secret preview')",
                Timestamp.from(ts(at)), patient, ref, category, kind);
    }

}
