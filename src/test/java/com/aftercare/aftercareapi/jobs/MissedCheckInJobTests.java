package com.aftercare.aftercareapi.jobs;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.Services.jobs.MissedCheckInJob;
import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D is the discharge day (the day of the MutableClock default, 2026-10-14); times are Toronto local. */
class MissedCheckInJobTests extends IntegrationTest {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final LocalDate D = LocalDate.of(2026, 10, 14);

    @Autowired
    MissedCheckInJob job;

    @BeforeEach
    void closeOtherFollowUps() {
        // the database is shared by many tests: only this test's follow-up should be ACTIVE
        jdbc.update("update follow_ups set status = 'CLOSED', closed_at = ? where status = 'ACTIVE'", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    }

    private void at(LocalDate day, int hour, int minute) {
        clock.set(LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(TORONTO).toInstant());
    }

    private record Enrolled(long patient, long followUp, String code) {
    }

    private Enrolled enrol(int days) throws Exception {
        long p = newPatient();
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", D.toString(), days);
        return new Enrolled(p, f.get("followUp").get("id").asLong(), f.get("accessCode").asText());
    }

    private List<Map<String, Object>> alerts(long followUp) {
        return jdbc.queryForList("select id, alert_type, missed_date, severity, message_en, message_fr, status, resolved_by, resolution_note, "
                + "check_in_id, link_id, answer_value from alerts where follow_up_id = ? order by missed_date", followUp);
    }

    private void checkIn(Enrolled e) throws Exception {
        mockMvc.perform(post("/api/public/q/" + e.code()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(2, 37.0, false))).andExpect(status().isCreated());
    }

    @Test
    void nothingBeforeTheCutoffThenOneMediumAlertAndIdempotent() throws Exception {
        Enrolled e = enrol(7);
        at(D.plusDays(1), 19, 59);
        job.runOnce();
        assertEquals(0, alerts(e.followUp()).size());
        at(D.plusDays(1), 20, 0);
        job.runOnce();
        List<Map<String, Object>> rows = alerts(e.followUp());
        assertEquals(1, rows.size());
        Map<String, Object> a = rows.get(0);
        assertEquals("MISSED_CHECKIN", a.get("alert_type"));
        assertEquals(D.plusDays(1), ((java.sql.Date) a.get("missed_date")).toLocalDate());
        assertEquals("MEDIUM", a.get("severity"));
        assertEquals("No check-in received on 2026-10-15", a.get("message_en"));
        assertEquals("Aucun suivi reçu le 2026-10-15", a.get("message_fr"));
        assertEquals("OPEN", a.get("status"));
        assertNull(a.get("check_in_id"));
        assertNull(a.get("link_id"));
        assertNull(a.get("answer_value"));
        // idempotent
        job.runOnce();
        job.runOnce();
        assertEquals(1, alerts(e.followUp()).size());
        // activity row on the timeline
        List<String> summaries = jdbc.queryForList("select summary from patient_activity where patient_id = ? and kind = 'ALERT_CREATED'", String.class, e.patient());
        assertEquals(List.of("Medium alert: No check-in received on 2026-10-15"), summaries);
        assertEquals("SYSTEM", jdbc.queryForObject("select actor_type from patient_activity where patient_id = ? and kind = 'ALERT_CREATED'", String.class, e.patient()));
    }

    @Test
    void twoMissedDaysInARowEscalateToHigh() throws Exception {
        Enrolled e = enrol(7);
        at(D.plusDays(1), 20, 0);
        job.runOnce();
        at(D.plusDays(2), 20, 0);
        job.runOnce();
        List<Map<String, Object>> rows = alerts(e.followUp());
        assertEquals(2, rows.size());
        assertEquals("MEDIUM", rows.get(0).get("severity"));
        Map<String, Object> second = rows.get(1);
        assertEquals("HIGH", second.get("severity"));
        assertEquals("No check-in received on 2026-10-15 and 2026-10-16", second.get("message_en"));
        assertEquals("Aucun suivi reçu le 2026-10-15 ni le 2026-10-16", second.get("message_fr"));
        List<String> summaries = jdbc.queryForList("select summary from patient_activity where patient_id = ? and kind = 'ALERT_CREATED' order by id", String.class, e.patient());
        assertEquals("High alert: No check-in received on 2026-10-15 and 2026-10-16", summaries.get(1));
    }

    @Test
    void aCheckInBeforeTheCutoffPreventsThatDaysAlert() throws Exception {
        Enrolled e = enrol(7);
        at(D.plusDays(1), 20, 0);
        job.runOnce();
        at(D.plusDays(2), 20, 0);
        job.runOnce();
        at(D.plusDays(3), 10, 0);
        checkIn(e);
        at(D.plusDays(3), 20, 0);
        job.runOnce();
        assertEquals(2, alerts(e.followUp()).size());
        // and the day after, D+3 counts as received: D+4 is MEDIUM, not HIGH
        at(D.plusDays(4), 20, 0);
        job.runOnce();
        List<Map<String, Object>> rows = alerts(e.followUp());
        assertEquals(3, rows.size());
        assertEquals("MEDIUM", rows.get(2).get("severity"));
        assertEquals("No check-in received on 2026-10-18", rows.get(2).get("message_en"));
    }

    @Test
    void theDischargeDayIsNeverAlertedAndEnrolmentCountsFromTheDayAfter() throws Exception {
        // discharged on D, enrolled on D+2: the first expected day is D+3
        at(D.plusDays(2), 12, 0);
        Enrolled e = enrol(7);
        at(D.plusDays(2), 20, 30);
        job.runOnce();
        assertEquals(0, alerts(e.followUp()).size());
        at(D.plusDays(3), 20, 0);
        job.runOnce();
        List<Map<String, Object>> rows = alerts(e.followUp());
        assertEquals(1, rows.size());
        assertEquals(D.plusDays(3), ((java.sql.Date) rows.get(0).get("missed_date")).toLocalDate());
        assertEquals("MEDIUM", rows.get(0).get("severity")); // D+2 was not an expected day
        // a same-day enrolment: day 0 never alerted even after the cutoff
        at(D, 12, 0);
        Enrolled today = enrol(7);
        at(D, 21, 0);
        job.runOnce();
        assertEquals(0, alerts(today.followUp()).size());
    }

    @Test
    void closedFollowUpsAndTheEndDateAreRespected() throws Exception {
        Enrolled closed = enrol(7);
        assertEquals(200, call(post("/api/follow-ups/" + closed.followUp() + "/close")).getResponse().getStatus());
        Enrolled shortOne = enrol(2); // ends D+2
        at(D.plusDays(4), 20, 0);
        job.runOnce();
        assertEquals(0, alerts(closed.followUp()).size());
        List<Map<String, Object>> rows = alerts(shortOne.followUp());
        // catch-up window starts at D+2 and nothing after the end date
        assertEquals(1, rows.size());
        assertEquals(D.plusDays(2), ((java.sql.Date) rows.get(0).get("missed_date")).toLocalDate());
    }

    @Test
    void catchUpIsLimitedToTwoPastDays() throws Exception {
        Enrolled e = enrol(7);
        at(D.plusDays(6), 20, 0);
        job.runOnce();
        List<Map<String, Object>> rows = alerts(e.followUp());
        assertEquals(3, rows.size());
        assertEquals(D.plusDays(4), ((java.sql.Date) rows.get(0).get("missed_date")).toLocalDate());
        // days before the window still count for the severity
        assertEquals("HIGH", rows.get(0).get("severity"));
        assertEquals("No check-in received on 2026-10-17 and 2026-10-18", rows.get(0).get("message_en"));
    }

    @Test
    void aLateCheckInResolvesThatDaysAlert() throws Exception {
        Enrolled e = enrol(7);
        at(D.plusDays(1), 20, 0);
        job.runOnce();
        at(D.plusDays(1), 20, 30);
        checkIn(e);
        Map<String, Object> a = alerts(e.followUp()).stream().filter(r -> r.get("alert_type").equals("MISSED_CHECKIN")).findFirst().orElseThrow();
        assertEquals("RESOLVED", a.get("status"));
        assertEquals("system:checkin", a.get("resolved_by"));
        assertEquals("Check-in received", a.get("resolution_note"));
        List<Map<String, Object>> act = jdbc.queryForList("select actor_type, summary, ref_id from patient_activity where patient_id = ? and kind = 'ALERT_RESOLVED'", e.patient());
        assertEquals(1, act.size());
        assertEquals("SYSTEM", act.get(0).get("actor_type"));
        assertEquals("Alert resolved: No check-in received on 2026-10-15", act.get(0).get("summary"));
        assertEquals(((Number) a.get("id")).longValue(), ((Number) act.get(0).get("ref_id")).longValue());
        // the job does not re-create it
        job.runOnce();
        assertEquals(1, alerts(e.followUp()).size());
    }

    @Test
    void disabledInSettingsMeansNothing() throws Exception {
        Enrolled e = enrol(7);
        jdbc.update("update clinic_settings set missed_checkin_enabled = false");
        at(D.plusDays(1), 20, 0);
        job.runOnce();
        assertEquals(0, alerts(e.followUp()).size());
        // and a configured cutoff is honoured
        jdbc.update("update clinic_settings set missed_checkin_enabled = true, missed_checkin_cutoff = time '21:30:00'");
        job.runOnce();
        assertEquals(0, alerts(e.followUp()).size());
        at(D.plusDays(1), 21, 30);
        job.runOnce();
        assertEquals(1, alerts(e.followUp()).size());
    }

    @Autowired com.aftercare.aftercareapi.Services.jobs.JobLockService locks;
    @Autowired com.aftercare.aftercareapi.Services.ClinicSettingsService settingsService;
    @Autowired com.aftercare.aftercareapi.Repositories.FollowUpRepository followUpRepo;
    @Autowired com.aftercare.aftercareapi.Repositories.CheckInRepository checkInRepo;
    @Autowired com.aftercare.aftercareapi.Repositories.AlertRepository alertRepo;
    @Autowired com.aftercare.aftercareapi.Services.ActivityService activityService;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    /**
     * A job whose day-level reads are stale: the idempotency sets (existing alerts, existing check-ins) come back empty,
     * as if another instance or a patient had acted after they were read. Everything else hits the real database.
     */
    private com.aftercare.aftercareapi.Repositories.AlertRepository staleAlerts;

    private MissedCheckInJob staleJob(boolean hideAlerts, boolean hideCheckIns) {
        var alerts = org.mockito.Mockito.mock(com.aftercare.aftercareapi.Repositories.AlertRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(alertRepo));
        var checkIns = org.mockito.Mockito.mock(com.aftercare.aftercareapi.Repositories.CheckInRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(checkInRepo));
        if (hideAlerts) {
            org.mockito.Mockito.doReturn(List.of()).when(alerts).missedKeys(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
        if (hideCheckIns) {
            org.mockito.Mockito.doReturn(List.of()).when(checkIns).dates(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
        staleAlerts = alerts;
        MissedCheckInJob j = new MissedCheckInJob(locks, settingsService, followUpRepo, checkIns, alerts, activityService, clock, txManager);
        org.springframework.test.util.ReflectionTestUtils.setField(j, "em", em);
        return j;
    }

    @Test
    void anExistingAlertForTheSameDayIsNotDuplicated() throws Exception {
        Enrolled e = enrol(7);
        long fu = e.followUp();
        jdbc.update("insert into alerts (patient_id, follow_up_id, alert_type, missed_date, severity, message_en, message_fr, status, created_at, version) "
                + "values (?, ?, 'MISSED_CHECKIN', date '2026-10-15', 'MEDIUM', 'x', 'x', 'OPEN', ?, 0)", e.patient(), fu,
                Timestamp.from(Instant.parse("2026-10-15T23:00:00Z")));
        at(D.plusDays(1), 20, 0);
        assertDoesNotThrow(() -> job.runOnce());
        assertEquals(1, alerts(fu).size());
    }

    @Test
    void aConcurrentDuplicateHitsTheUniqueKeyAndIsSwallowed() throws Exception {
        Enrolled e = enrol(7);
        long fu = e.followUp();
        jdbc.update("insert into alerts (patient_id, follow_up_id, alert_type, missed_date, severity, message_en, message_fr, status, created_at, version) "
                + "values (?, ?, 'MISSED_CHECKIN', date '2026-10-15', 'MEDIUM', 'x', 'x', 'OPEN', ?, 0)", e.patient(), fu,
                Timestamp.from(Instant.parse("2026-10-15T23:00:00Z")));
        at(D.plusDays(1), 20, 0);
        // this instance did not see the alert when it read the existing ones, so it tries to insert it: DataIntegrityViolation
        MissedCheckInJob stale = staleJob(true, false);
        assertEquals(0, stale.runOnce(), "no alert is reported as created");
        org.mockito.Mockito.verify(staleAlerts, org.mockito.Mockito.atLeastOnce()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        assertEquals(1, alerts(fu).size());
    }

    @Test
    void aCheckInThatArrivedAfterTheDayWasReadPreventsTheAlert() throws Exception {
        Enrolled e = enrol(7);
        at(D.plusDays(1), 10, 0);
        checkIn(e); // a check-in for 2026-10-15
        at(D.plusDays(1), 20, 0);
        MissedCheckInJob stale = staleJob(false, true); // its day-level read does not show the check-in
        assertEquals(0, stale.runOnce());
        assertEquals(0, alerts(e.followUp()).size());
        org.mockito.Mockito.verify(staleAlerts, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theAlertDtoAndTheReadModelsShowMissedCheckIns() throws Exception {
        Enrolled e = enrol(7);
        JsonNode before = json(as(asRole(AccessRole.ADMIN), get("/api/reports/summary").param("from", "2026-10-01").param("to", "2026-10-31")));
        at(D.plusDays(1), 20, 0);
        job.runOnce();

        JsonNode mine = null;
        for (JsonNode a : json(as(asRole(AccessRole.NURSE), get("/api/alerts")))) {
            if (a.get("followUpId").asLong() == e.followUp()) {
                mine = a;
            }
        }
        assertNotNull(mine, "listed in /api/alerts");
        assertEquals("MISSED_CHECKIN", mine.get("type").asText());
        assertEquals("2026-10-15", mine.get("missedDate").asText());
        for (String nullField : List.of("checkInId", "checkInDate", "linkId", "questionLabel", "answerValue")) {
            assertTrue(mine.has(nullField) && mine.get(nullField).isNull(), nullField);
        }
        assertEquals("No check-in received on 2026-10-15", mine.get("message").asText());
        assertEquals("Aucun suivi reçu le 2026-10-15", mine.get("messageFr").asText());

        JsonNode perPatient = json(as(asRole(AccessRole.NURSE), get("/api/patients/" + e.patient() + "/alerts")));
        assertEquals(1, perPatient.size());
        assertEquals("MISSED_CHECKIN", perPatient.get(0).get("type").asText());

        JsonNode timeline = json(as(asRole(AccessRole.NURSE), get("/api/patients/" + e.patient() + "/timeline")));
        assertEquals("ALERT_CREATED", timeline.get("items").get(0).get("kind").asText());
        assertEquals("Medium alert: No check-in received on 2026-10-15", timeline.get("items").get(0).get("summary").asText());

        JsonNode after = json(as(asRole(AccessRole.ADMIN), get("/api/reports/summary").param("from", "2026-10-01").param("to", "2026-10-31")));
        assertEquals(before.get("alerts").get("total").asInt() + 1, after.get("alerts").get("total").asInt());
        assertEquals(before.get("alerts").get("medium").asInt() + 1, after.get("alerts").get("medium").asInt());

        // acknowledging and resolving work like any alert
        long id = mine.get("id").asLong();
        assertEquals(200, call(post("/api/alerts/" + id + "/acknowledge")).getResponse().getStatus());
        assertEquals(200, call(post("/api/alerts/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"called\"}")).getResponse().getStatus());
    }
}
