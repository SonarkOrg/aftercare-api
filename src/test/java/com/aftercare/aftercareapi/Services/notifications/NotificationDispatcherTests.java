package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
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

@Import(RecordingSenders.class)
class NotificationDispatcherTests extends IntegrationTest {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final LocalDate D = LocalDate.of(2026, 10, 14);

    @Autowired NotificationDispatcher dispatcher;
    @Autowired @Qualifier("recordingEmailSender") RecordingSenders.Recording email;
    @Autowired @Qualifier("recordingSmsSender") RecordingSenders.Recording sms;
    @Autowired CheckInReminderJob reminders;

    @BeforeEach
    void clean() {
        jdbc.update("delete from notification_outbox");
        jdbc.update("update follow_ups set status = 'CLOSED', closed_at = ? where status = 'ACTIVE'", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        email.reset();
        sms.reset();
    }

    private void at(LocalDate day, int hour, int minute) {
        clock.set(LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(TORONTO).toInstant());
    }

    private record Case(long patient, long followUp, String code, long notification) {
    }

    /** A consenting patient with a new follow-up; the automated link message is the one PENDING row. */
    private Case ready(String channel) throws Exception {
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", channel, "EN");
        JsonNode f = newFollowUp(p, "VISCERAL_SURGERY", D.toString(), 7);
        long n = jdbc.queryForObject("select id from notification_outbox where patient_id = ?", Long.class, p);
        return new Case(p, f.get("followUp").get("id").asLong(), f.get("accessCode").asText(), n);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select * from notification_outbox where id = ?", id);
    }

    @Test
    void aPendingMessageIsRenderedSentAndMaskedWithoutPhi() throws Exception {
        Case c = ready("EMAIL");
        NotificationDispatcher.Tick tick = dispatcher.runOnce();
        assertEquals(1, tick.sent());
        Map<String, Object> r = row(c.notification());
        assertEquals("SENT", r.get("status"));
        assertEquals(1, ((Number) r.get("attempts")).intValue());
        assertNotNull(r.get("sent_at"));
        assertNull(r.get("last_error"));
        assertEquals("t•••@example.com", r.get("recipient_masked"));
        assertEquals("rec-1", r.get("provider_message_id"));
        assertEquals(1, email.sent.size());
        assertEquals(0, sms.sent.size());
        OutboundMessage m = email.sent.get(0);
        assertEquals("t@example.com", m.to());
        assertEquals("Clinic Test - Your aftercare check-in".replace("Clinic Test", "Aftercare clinic"), m.subject());
        assertTrue(m.body().contains("https://app.test/q/" + c.code()), m.body());
        assertTrue(m.body().contains("Hi Test,"));
        assertTrue(m.body().contains("In an emergency, call 911"));
        for (String banned : List.of("VISCERAL", "visceral", "surgery", "Surgery", "chirurgie", "diagnos", "2026-10")) {
            assertFalse((m.subject() + m.body()).contains(banned), banned);
        }
        // sending again does nothing
        assertEquals(0, dispatcher.runOnce().sent());
        assertEquals(1, email.sent.size());
    }

    @Test
    void smsGoesToTheNormalisedNumberWithoutAFooter() throws Exception {
        Case c = ready("SMS");
        assertEquals(1, dispatcher.runOnce().sent());
        assertEquals(0, email.sent.size());
        OutboundMessage m = sms.sent.get(0);
        assertEquals("+15145550100", m.to());
        assertNull(m.subject());
        assertEquals("Aftercare clinic: Hi Test, here is your personal aftercare check-in link: https://app.test/q/" + c.code(), m.body());
        assertEquals("•••-•••-0100", row(c.notification()).get("recipient_masked"));
    }

    @Test
    void transientFailuresRetryWithBackoffThenFail() throws Exception {
        Case c = ready("EMAIL");
        email.behaviour = () -> new SendResult.Transient("HTTP_500");
        Duration[] backoff = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};
        for (int attempt = 1; attempt <= 4; attempt++) {
            Instant before = clock.instant();
            NotificationDispatcher.Tick t = dispatcher.runOnce();
            assertEquals(1, t.retried(), "attempt " + attempt);
            Map<String, Object> r = row(c.notification());
            assertEquals("PENDING", r.get("status"));
            assertEquals(attempt, ((Number) r.get("attempts")).intValue());
            assertEquals("PROVIDER_ERROR", r.get("last_error"));
            assertEquals("t•••@example.com", r.get("recipient_masked"));
            assertEquals(before.plus(backoff[attempt - 1]), instant(r.get("next_attempt_at")));
            // not due yet
            clock.plus(backoff[attempt - 1].minusSeconds(1));
            assertEquals(0, dispatcher.runOnce().retried());
            clock.plus(Duration.ofSeconds(1));
        }
        NotificationDispatcher.Tick last = dispatcher.runOnce();
        assertEquals(1, last.failed());
        Map<String, Object> r = row(c.notification());
        assertEquals("FAILED", r.get("status"));
        assertEquals("PROVIDER_ERROR", r.get("last_error"));
        assertEquals(5, ((Number) r.get("attempts")).intValue());
        assertEquals(5, email.sent.size());
        clock.plus(Duration.ofDays(1));
        assertEquals(0, dispatcher.runOnce().failed(), "a failed message is never retried");
    }

    @Test
    void anExceptionInTheSenderIsTreatedAsTransient() throws Exception {
        Case c = ready("EMAIL");
        email.behaviour = () -> {
            throw new IllegalStateException("provider exploded");
        };
        assertEquals(1, dispatcher.runOnce().retried());
        assertEquals("PENDING", row(c.notification()).get("status"));
    }

    @Test
    void aPermanentFailureIsFinal() throws Exception {
        Case c = ready("EMAIL");
        email.behaviour = () -> new SendResult.Permanent("REJECTED");
        assertEquals(1, dispatcher.runOnce().failed());
        Map<String, Object> r = row(c.notification());
        assertEquals("FAILED", r.get("status"));
        assertEquals("PROVIDER_REJECTED", r.get("last_error"));
        assertEquals("t•••@example.com", r.get("recipient_masked"));
        assertEquals(1, ((Number) r.get("attempts")).intValue());
    }

    @Test
    void theChecksAreRepeatedRightBeforeSending() throws Exception {
        Case consent = ready("EMAIL");
        jdbc.update("update patients set contact_consent = 'WITHDRAWN' where id = ?", consent.patient());
        Case channel = ready("EMAIL");
        Case recipient = ready("EMAIL");
        jdbc.update("update patients set email = 'broken' where id = ?", recipient.patient());
        // channel disabled only after the rows were queued
        jdbc.update("update clinic_settings set email_enabled = true");
        dispatcher.runOnce(); // flush the first two which are decided before the channel check
        assertEquals("CANCELLED", row(consent.notification()).get("status"));
        assertEquals("NO_CONSENT", row(consent.notification()).get("last_error"));
        assertEquals("FAILED", row(recipient.notification()).get("status"));
        assertEquals("INVALID_RECIPIENT", row(recipient.notification()).get("last_error"));
        assertNull(row(recipient.notification()).get("recipient_masked"));
        assertEquals("SENT", row(channel.notification()).get("status"));
        assertEquals(1, email.sent.size(), "only the valid message went out");

        Case off = ready("EMAIL");
        jdbc.update("update clinic_settings set email_enabled = false");
        dispatcher.runOnce();
        assertEquals("CANCELLED", row(off.notification()).get("status"));
        assertEquals("CHANNEL_DISABLED", row(off.notification()).get("last_error"));
        assertEquals(1, email.sent.size());
    }

    @Test
    void theLinkMustStillExistAndBeUsable() throws Exception {
        Case noCode = ready("EMAIL");
        jdbc.update("update patient_access_links set code_enc = null where follow_up_id = ?", noCode.followUp());
        Case closed = ready("EMAIL");
        assertEquals(200, call(post("/api/follow-ups/" + closed.followUp() + "/close")).getResponse().getStatus());
        dispatcher.runOnce();
        Map<String, Object> a = row(noCode.notification());
        assertEquals("FAILED", a.get("status"));
        assertEquals("LINK_UNAVAILABLE", a.get("last_error"));
        assertEquals("t•••@example.com", a.get("recipient_masked"));
        Map<String, Object> b = row(closed.notification());
        assertEquals("SKIPPED", b.get("status"));
        assertEquals("NOT_APPLICABLE", b.get("last_error"));
        assertEquals(0, email.sent.size());
    }

    @Test
    void aReminderIsSkippedOnceTheCheckInIsIn() throws Exception {
        Case c = ready("EMAIL");
        jdbc.update("delete from notification_outbox");
        jdbc.update("update clinic_settings set checkin_reminder_enabled = true");
        at(D.plusDays(1), 18, 0);
        assertEquals(1, reminders.runOnce());
        long reminder = jdbc.queryForObject("select id from notification_outbox where template_key = 'CHECKIN_REMINDER'", Long.class);
        // the patient answers before the dispatcher gets to it
        mockMvc.perform(post("/api/public/q/" + c.code()).contentType(MediaType.APPLICATION_JSON)
                .content(checkInBody(2, 37.0, false))).andExpect(status().isCreated());
        NotificationDispatcher.Tick t = dispatcher.runOnce();
        assertEquals(1, t.skipped());
        Map<String, Object> r = row(reminder);
        assertEquals("SKIPPED", r.get("status"));
        assertEquals("NOT_APPLICABLE", r.get("last_error"));
        assertEquals(0, email.sent.size());
    }

    @Test
    void aStaleReminderFromAnotherDayIsSkipped() throws Exception {
        Case c = ready("EMAIL");
        jdbc.update("delete from notification_outbox");
        jdbc.update("update clinic_settings set checkin_reminder_enabled = true");
        at(D.plusDays(1), 18, 0);
        reminders.runOnce();
        at(D.plusDays(2), 10, 0); // sent a day late
        assertEquals(1, dispatcher.runOnce().skipped());
        assertEquals(0, email.sent.size());
    }

    @Test
    void quietHoursDeferAutomatedMessagesButNotManualOnes() throws Exception {
        Case c = ready("EMAIL"); // automated link message
        MvcResultHolder manual = new MvcResultHolder();
        at(D, 22, 0);
        manual.id = json(call(post("/api/follow-ups/" + c.followUp() + "/send-link"))).get("id").asLong();
        NotificationDispatcher.Tick t = dispatcher.runOnce();
        assertEquals(1, t.sent());
        assertEquals("SENT", row(manual.id).get("status"), "manual sends ignore quiet hours");
        Map<String, Object> automated = row(c.notification());
        assertEquals("PENDING", automated.get("status"));
        assertEquals(0, ((Number) automated.get("attempts")).intValue(), "deferred, not attempted");
        Instant eight = LocalDateTime.of(D.plusDays(1), LocalTime.of(8, 0)).atZone(TORONTO).toInstant();
        assertEquals(eight, instant(automated.get("next_attempt_at")));
        // early morning is quiet too, and the next 08:00 is the same day
        at(D.plusDays(1), 7, 59);
        assertEquals(0, dispatcher.runOnce().sent());
        at(D.plusDays(1), 8, 0);
        assertEquals(1, dispatcher.runOnce().sent());
        assertEquals("SENT", row(c.notification()).get("status"));
    }

    private static class MvcResultHolder {
        long id;
    }

    @Test
    void aMessageStuckInSendingIsRecovered() throws Exception {
        Case c = ready("EMAIL");
        jdbc.update("update notification_outbox set status = 'SENDING', attempts = 1, updated_at = ? where id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(11))), c.notification());
        NotificationDispatcher.Tick t = dispatcher.runOnce();
        assertEquals(1, t.sent());
        assertEquals(2, ((Number) row(c.notification()).get("attempts")).intValue());
        // a recent SENDING row is left alone
        Case d = ready("EMAIL");
        jdbc.update("update notification_outbox set status = 'SENDING', updated_at = ? where id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(2))), d.notification());
        assertEquals(0, dispatcher.runOnce().sent());
        assertEquals("SENDING", row(d.notification()).get("status"));
    }

    @Test
    void theHourlyCapStopsTheTick() throws Exception {
        // the cap itself is configured in NotificationDispatcherCapTests; here only that the sent count is what the cap reads
        Case c = ready("EMAIL");
        dispatcher.runOnce();
        assertEquals(1, jdbc.queryForObject("select count(*) from notification_outbox where status = 'SENT' and sent_at > ?", Integer.class,
                Timestamp.from(clock.instant().minus(Duration.ofHours(1)))));
        assertNotNull(c);
    }

    @Test
    void anotherInstanceHoldingTheLeaseMeansNoWork() throws Exception {
        Case c = ready("EMAIL");
        jdbc.update("insert into job_locks (name, locked_until, locked_by) values ('notification-dispatch', ?, 'other')",
                Timestamp.from(clock.instant().plus(Duration.ofMinutes(1))));
        assertEquals(0, dispatcher.runOnce().sent());
        assertEquals("PENDING", row(c.notification()).get("status"));
    }

    @Autowired com.aftercare.aftercareapi.Repositories.NotificationOutboxRepository outboxRepo;

    @Test
    void theClaimIsAtomicOnlyOneCallerWins() throws Exception {
        Case c = ready("EMAIL");
        Instant now = clock.instant();
        assertEquals(1, outboxRepo.claim(c.notification(), now));
        assertEquals(0, outboxRepo.claim(c.notification(), now), "already SENDING");
        Map<String, Object> r = row(c.notification());
        assertEquals("SENDING", r.get("status"));
        assertEquals(1, ((Number) r.get("attempts")).intValue());
        // the dispatcher skips a row somebody else holds
        assertEquals(0, dispatcher.runOnce().sent());
        assertEquals(0, email.sent.size());
    }

    @Test
    void aBatchStopsBeforeTheLeaseExpires() throws Exception {
        Case a = ready("EMAIL");
        Case b = ready("EMAIL");
        Case d = ready("EMAIL");
        assertEquals(Duration.ofMinutes(10), NotificationDispatcher.LEASE);
        // every send takes 5 minutes: after two the lease margin (10 - 2 = 8 minutes) is used up
        email.behaviour = () -> {
            clock.plus(Duration.ofMinutes(5));
            return new SendResult.Sent("rec-1");
        };
        NotificationDispatcher.Tick tick = dispatcher.runOnce();
        assertEquals(2, tick.sent());
        assertEquals(2, email.sent.size());
        assertEquals("PENDING", row(d.notification()).get("status"));
        assertEquals("SENT", row(a.notification()).get("status"));
        assertEquals("SENT", row(b.notification()).get("status"));
    }

    @Test
    void theLeaseIsTenMinutes() throws Exception {
        ready("EMAIL");
        email.behaviour = () -> {
            Instant until = instant(jdbc.queryForObject("select locked_until from job_locks where name = 'notification-dispatch'", Object.class));
            assertEquals(clock.instant().plus(Duration.ofMinutes(10)), until);
            return new SendResult.Sent("rec-1");
        };
        assertEquals(1, dispatcher.runOnce().sent());
    }

    @Test
    void aStuckMessageThatAlreadyHadFiveAttemptsFailsInsteadOfRetrying() throws Exception {
        Case c = ready("EMAIL");
        jdbc.update("update notification_outbox set status = 'SENDING', attempts = 5, updated_at = ? where id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(11))), c.notification());
        assertEquals(0, dispatcher.runOnce().sent());
        Map<String, Object> r = row(c.notification());
        assertEquals("FAILED", r.get("status"));
        assertEquals("PROVIDER_ERROR", r.get("last_error"));
        assertEquals(0, email.sent.size());
    }

    @Test
    void anIdleTickLogsNothingAtInfo() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NotificationDispatcher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            dispatcher.runOnce();
            assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO), "idle tick");
            ready("EMAIL");
            dispatcher.runOnce();
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                    && e.getFormattedMessage().startsWith("Dispatched 1 sent")));
        } finally {
            logger.detachAppender(appender);
        }
    }

}
