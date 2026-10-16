package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReminderJobTests extends IntegrationTest {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final LocalDate D = LocalDate.of(2026, 10, 14);

    @Autowired CheckInReminderJob checkInReminders;
    @Autowired AppointmentReminderJob appointmentReminders;

    @BeforeEach
    void isolate() {
        jdbc.update("update follow_ups set status = 'CLOSED', closed_at = ? where status = 'ACTIVE'", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        jdbc.update("update appointments set status = 'CANCELLED' where status in ('SCHEDULED','READY','NEEDS_REVIEW')");
    }

    private void at(LocalDate day, int hour, int minute) {
        clock.set(LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(TORONTO).toInstant());
    }

    private List<Map<String, Object>> rows(long patient, String template) {
        return jdbc.queryForList("select * from notification_outbox where patient_id = ? and template_key = ? order by id", patient, template);
    }

    private record Enrolled(long patient, long followUp, String code) {
    }

    private Enrolled enrol() throws Exception {
        settings(true, true);
        jdbc.update("update clinic_settings set checkin_reminder_enabled = true");
        long p = newPatient();
        consent(p, "GRANTED", "EMAIL", null);
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", D.toString(), 7);
        return new Enrolled(p, f.get("followUp").get("id").asLong(), f.get("accessCode").asText());
    }

    @Test
    void theDailyReminderIsSentOnlyBetweenTheReminderTimeAndTheCutoff() throws Exception {
        Enrolled e = enrol();
        at(D.plusDays(1), 17, 59);
        assertEquals(0, checkInReminders.runOnce());
        at(D.plusDays(1), 18, 0);
        assertEquals(1, checkInReminders.runOnce());
        List<Map<String, Object>> r = rows(e.patient(), "CHECKIN_REMINDER");
        assertEquals(1, r.size());
        assertEquals("CHECKIN_REMINDER:" + e.followUp() + ":2026-10-15", r.get(0).get("dedupe_key"));
        assertEquals(true, r.get(0).get("automated"));
        assertEquals("PENDING", r.get(0).get("status"));
        // once per day
        at(D.plusDays(1), 18, 10);
        assertEquals(0, checkInReminders.runOnce());
        assertEquals(1, rows(e.patient(), "CHECKIN_REMINDER").size());
        // the next day gets its own reminder
        at(D.plusDays(2), 18, 30);
        assertEquals(1, checkInReminders.runOnce());
        assertEquals(2, rows(e.patient(), "CHECKIN_REMINDER").size());
        // not at or after the missed check-in cutoff
        jdbc.update("delete from notification_outbox where patient_id = ?", e.patient());
        at(D.plusDays(3), 20, 0);
        assertEquals(0, checkInReminders.runOnce());
    }

    @Test
    void noReminderForTheDischargeDayOrAfterTodaysCheckIn() throws Exception {
        Enrolled e = enrol();
        at(D, 18, 30);
        assertEquals(0, checkInReminders.runOnce(), "day 0 is never an expected day");
        at(D.plusDays(1), 9, 0);
        mockMvc.perform(post("/api/public/q/" + e.code()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(2, 37.0, false))).andExpect(status().isCreated());
        at(D.plusDays(1), 18, 30);
        assertEquals(0, checkInReminders.runOnce());
        assertEquals(0, rows(e.patient(), "CHECKIN_REMINDER").size());
    }

    @Test
    void theReminderRespectsTheSettingsAndTheConsentGate() throws Exception {
        Enrolled e = enrol();
        jdbc.update("update clinic_settings set checkin_reminder_enabled = false");
        at(D.plusDays(1), 18, 30);
        assertEquals(0, checkInReminders.runOnce());
        jdbc.update("update clinic_settings set checkin_reminder_enabled = true, checkin_reminder_time = time '19:00:00'");
        assertEquals(0, checkInReminders.runOnce());
        at(D.plusDays(1), 19, 0);
        jdbc.update("update patients set contact_consent = 'WITHDRAWN' where id = ?", e.patient());
        assertEquals(0, checkInReminders.runOnce(), "no consent, nothing is enqueued");
        jdbc.update("update patients set contact_consent = 'GRANTED' where id = ?", e.patient());
        assertEquals(1, checkInReminders.runOnce());
    }

    private record Booked(long patient, long appointment, String code) {
    }

    private Booked book(String scheduledAt) throws Exception {
        settings(true, true);
        jdbc.update("update clinic_settings set appointment_reminder_enabled = true");
        long p = newPatient();
        consent(p, "GRANTED", "EMAIL", null);
        JsonNode a = newAppointment(p, "GENERAL_CONSULT", "ORTHOPEDIC_SURGERY", scheduledAt);
        return new Booked(p, a.get("appointment").get("id").asLong(), a.get("accessCode").asText());
    }

    @Test
    void theAppointmentReminderFiresInsideTheWindowForAppointmentsBookedBeforeIt() throws Exception {
        Booked b = book("2026-10-19T09:00:00"); // booked on 2026-10-14 12:00, five days ahead
        at(D.plusDays(1), 9, 0);
        assertEquals(0, appointmentReminders.runOnce(), "more than 48 hours before");
        at(LocalDate.of(2026, 10, 17), 8, 59);
        assertEquals(0, appointmentReminders.runOnce(), "49 hours and 1 minute before");
        at(LocalDate.of(2026, 10, 17), 9, 0);
        assertEquals(1, appointmentReminders.runOnce());
        List<Map<String, Object>> r = rows(b.patient(), "APPOINTMENT_REMINDER");
        assertEquals(1, r.size());
        assertEquals("APPOINTMENT_REMINDER:" + b.appointment(), r.get(0).get("dedupe_key"));
        assertEquals(b.appointment(), ((Number) r.get(0).get("appointment_id")).longValue());
        // only once
        at(LocalDate.of(2026, 10, 18), 9, 0);
        assertEquals(0, appointmentReminders.runOnce());
        assertEquals(1, rows(b.patient(), "APPOINTMENT_REMINDER").size());
    }

    @Test
    void noReminderWithAnIntakeAfterTheStartOrInsideTheBookingWindow() throws Exception {
        // the link message was just sent for an appointment booked inside the window
        Booked inside = book("2026-10-15T09:00:00");
        at(D, 12, 30);
        assertEquals(0, appointmentReminders.runOnce());
        assertEquals(0, rows(inside.patient(), "APPOINTMENT_REMINDER").size());

        Booked done = book("2026-10-25T09:00:00");
        mockMvc.perform(post("/api/public/q/" + done.code()).contentType(MediaType.APPLICATION_JSON)
                .content(intakeBody(CONSULT_URL, false, false, false))).andExpect(status().isCreated());
        at(LocalDate.of(2026, 10, 24), 9, 0);
        assertEquals(0, appointmentReminders.runOnce(), "the questionnaire is already complete");

        Booked started = book("2026-10-27T09:00:00");
        at(LocalDate.of(2026, 10, 27), 9, 30);
        assertEquals(0, appointmentReminders.runOnce(), "already past");
        assertEquals(0, rows(started.patient(), "APPOINTMENT_REMINDER").size());
    }

    @Test
    void cancelledAppointmentsAndDisabledSettingsGetNothing() throws Exception {
        Booked b = book("2026-10-26T09:00:00");
        at(LocalDate.of(2026, 10, 25), 9, 0);
        jdbc.update("update clinic_settings set appointment_reminder_enabled = false");
        assertEquals(0, appointmentReminders.runOnce());
        jdbc.update("update clinic_settings set appointment_reminder_enabled = true");
        jdbc.update("update appointments set status = 'CANCELLED' where id = ?", b.appointment());
        assertEquals(0, appointmentReminders.runOnce());
        jdbc.update("update appointments set status = 'READY' where id = ?", b.appointment());
        assertEquals(1, appointmentReminders.runOnce());
    }

    @Test
    void aLongerLeadTimeMovesTheWindow() throws Exception {
        Booked b = book("2026-10-26T09:00:00");
        at(LocalDate.of(2026, 10, 23), 10, 0); // 71 hours before
        assertEquals(0, appointmentReminders.runOnce(), "outside the default 48 hours");
        jdbc.update("update clinic_settings set appointment_reminder_hours = 72");
        assertEquals(1, appointmentReminders.runOnce());
        assertEquals(1, rows(b.patient(), "APPOINTMENT_REMINDER").size());
    }
}
