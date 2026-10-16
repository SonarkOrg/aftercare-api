package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Import(RecordingSenders.class)
@TestPropertySource(properties = {"aftercare.notifications.max-per-hour=2", "aftercare.notifications.batch-size=10"})
class NotificationDispatcherCapTests extends IntegrationTest {

    @Autowired NotificationDispatcher dispatcher;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("recordingEmailSender") RecordingSenders.Recording email;

    @BeforeEach
    void clean() {
        jdbc.update("delete from notification_outbox");
        email.reset();
    }

    @Test
    void atMostTheHourlyCapIsSentAndTheRestWaits() throws Exception {
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", "EMAIL", null);
        Instant now = clock.instant();
        for (int i = 0; i < 3; i++) {
            insertOutbox(p, "CHECKIN_REMINDER", "PENDING", false, now, 1L, null, null);
        }
        // CHECKIN_REMINDER rows need a follow-up to be applicable: use link messages for a real follow-up instead
        jdbc.update("delete from notification_outbox");
        long fu = newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", 7).get("followUp").get("id").asLong();
        jdbc.update("delete from notification_outbox");
        for (int i = 0; i < 3; i++) {
            insertOutbox(p, "FOLLOW_UP_LINK", "PENDING", false, now, fu, null, null);
        }
        assertEquals(2, dispatcher.runOnce().sent());
        assertEquals(2, email.sent.size());
        assertEquals(1, jdbc.queryForObject("select count(*) from notification_outbox where status = 'PENDING'", Integer.class));
        // still capped within the same hour
        clock.plus(Duration.ofMinutes(30));
        assertEquals(0, dispatcher.runOnce().sent());
        // an hour after the first sends there is room again
        clock.plus(Duration.ofMinutes(31));
        assertEquals(1, dispatcher.runOnce().sent());
        assertEquals(List.of("SENT", "SENT", "SENT"), jdbc.queryForList("select status from notification_outbox order by id", String.class));
    }
}
