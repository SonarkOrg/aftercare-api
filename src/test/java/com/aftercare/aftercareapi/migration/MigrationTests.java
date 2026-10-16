package com.aftercare.aftercareapi.migration;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The schema is built by Flyway on H2 and validated by Hibernate; these checks cover the V2+ constraints. */
class MigrationTests extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Test
    void v1ToV6AreAppliedSuccessfully() {
        List<MigrationInfo> applied = List.of(flyway.info().applied());
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), applied.stream().map(m -> m.getVersion().getVersion()).toList());
        for (MigrationInfo m : applied) {
            assertTrue(m.getState().isApplied() && !m.getState().isFailed(), m.getVersion() + " " + m.getState());
        }
        assertEquals(0, flyway.info().pending().length);
        assertEquals("baseline", applied.get(0).getDescription());
    }

    private long followUpWithAlerts() throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        mockMvc.perform(post("/api/public/q/" + f.get("accessCode").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(8, 38.4, true))).andExpect(status().isCreated());
        return jdbc.queryForObject("select follow_up_id from alerts where patient_id = ? limit 1", Long.class, p);
    }

    private void insertAlert(long patientFollowUp, String type, Long checkInId, String linkId, String answer, String missed) {
        long patientId = jdbc.queryForObject("select patient_id from follow_ups where id = ?", Long.class, patientFollowUp);
        jdbc.update("insert into alerts (patient_id, follow_up_id, check_in_id, alert_type, missed_date, link_id, answer_value, severity, "
                        + "message_en, message_fr, status, created_at, version) values (?, ?, ?, ?, " + (missed == null ? "null" : "date '" + missed + "'")
                        + ", ?, ?, 'MEDIUM', 'm', 'm', 'OPEN', ?, 0)",
                patientId, patientFollowUp, checkInId, type, linkId, answer, Timestamp.from(Instant.parse("2026-10-14T16:00:00Z")));
    }

    @Test
    void anInvalidAlertTypeIsRejected() throws Exception {
        long fu = followUpWithAlerts();
        Long checkIn = jdbc.queryForObject("select check_in_id from alerts where follow_up_id = ? limit 1", Long.class, fu);
        var ex = assertThrows(DataIntegrityViolationException.class,
                () -> insertAlert(fu, "BOGUS", checkIn, "PAIN", "9", null));
        assertTrue(ex.getMessage().toLowerCase().contains("alerts_alert_type_check"), ex.getMessage());
    }

    @Test
    void theAlertShapeCheckHolds() throws Exception {
        long fu = followUpWithAlerts();
        Long checkIn = jdbc.queryForObject("select check_in_id from alerts where follow_up_id = ? limit 1", Long.class, fu);
        // a missed check-in must not have a check-in; an answer alert must not have a missed date
        assertThrows(DataIntegrityViolationException.class, () -> insertAlert(fu, "MISSED_CHECKIN", checkIn, null, null, "2026-10-10"));
        assertThrows(DataIntegrityViolationException.class, () -> insertAlert(fu, "MISSED_CHECKIN", null, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> insertAlert(fu, "CHECK_IN_ANSWER", checkIn, "PAIN", "9", "2026-10-10"));
        assertThrows(DataIntegrityViolationException.class, () -> insertAlert(fu, "CHECK_IN_ANSWER", null, "PAIN", "9", null));
    }

    @Test
    void missedDateIsUniquePerFollowUpAndNullsAreFree() throws Exception {
        long fu = followUpWithAlerts();
        // several answer alerts of one check-in have a null missed_date: allowed
        assertTrue(jdbc.queryForObject("select count(*) from alerts where follow_up_id = ? and missed_date is null", Integer.class, fu) > 1);
        insertAlert(fu, "MISSED_CHECKIN", null, null, null, "2026-10-11");
        var ex = assertThrows(DataIntegrityViolationException.class, () -> insertAlert(fu, "MISSED_CHECKIN", null, null, null, "2026-10-11"));
        assertTrue(ex.getMessage().toLowerCase().contains("uk_alerts_follow_up_missed_date"), ex.getMessage());
        insertAlert(fu, "MISSED_CHECKIN", null, null, null, "2026-10-12");
    }

    @Test
    void auditEnumChecksCoverTheNewValues() {
        for (String action : List.of("STAFF_ROLE_CHANGED", "STAFF_BOOTSTRAPPED", "ACCESS_DENIED", "CONTACT_PREFERENCES_UPDATED",
                "NOTIFICATION_REQUESTED", "NOTIFICATION_SETTINGS_UPDATED", "RETENTION_SETTINGS_UPDATED", "RETENTION_DRY_RUN", "RETENTION_PURGE")) {
            jdbc.update("insert into audit_events (occurred_at, actor_sub, action, resource_type) values (?, 'm', ?, 'ENDPOINT')",
                    Timestamp.from(Instant.parse("2026-10-14T16:00:00Z")), action);
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into audit_events (occurred_at, actor_sub, action, resource_type) values (?, 'm', 'NOPE', 'PATIENT')",
                Timestamp.from(Instant.parse("2026-10-14T16:00:00Z"))));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into audit_events (occurred_at, actor_sub, action, resource_type) values (?, 'm', 'PATIENT_UPDATED', 'NOPE')",
                Timestamp.from(Instant.parse("2026-10-14T16:00:00Z"))));
    }

    @Test
    void v2BackfilledNoStaffWithoutAnAccessRole() {
        assertEquals(0, jdbc.queryForObject("select count(*) from staff_members where access_role is null", Integer.class));
    }
}
