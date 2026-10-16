package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Without a provider configured the logging senders are active: messages are marked SENT but nothing is delivered. */
class DefaultSendersTests extends IntegrationTest {

    @Autowired NotificationDispatcher dispatcher;
    @Autowired SenderRegistry senders;

    @Test
    void loggingSendersAreTheDefaultForBothChannels() {
        assertInstanceOf(LoggingEmailSender.class, senders.forChannel(com.aftercare.aftercareapi.Enums.NotificationChannel.EMAIL));
        assertInstanceOf(LoggingSmsSender.class, senders.forChannel(com.aftercare.aftercareapi.Enums.NotificationChannel.SMS));
    }

    @Test
    void theLoggingSenderMarksTheRowSentWithAMaskedRecipient() throws Exception {
        jdbc.update("delete from notification_outbox");
        settings(true, true);
        long p = newPatient();
        consent(p, "GRANTED", "SMS", "FR");
        newFollowUp(p, "VISCERAL_SURGERY", "2026-10-13", null);
        assertEquals(1, dispatcher.runOnce().sent());
        Map<String, Object> row = jdbc.queryForMap("select id, status, provider_message_id, recipient_masked, channel from notification_outbox where patient_id = ?", p);
        assertEquals("SENT", row.get("status"));
        assertEquals("log-" + row.get("id"), row.get("provider_message_id"));
        assertEquals("•••-•••-0100", row.get("recipient_masked"));
        assertEquals("SMS", row.get("channel"));
    }

    @Test
    void anOutboxRowCarriesNoMessageContent() {
        // the schema has no column that could hold a rendered message, a link or a code
        var columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'notification_outbox'", String.class);
        assertTrue(columns.contains("recipient_masked"));
        for (String forbidden : new String[]{"body", "subject", "message", "link", "code", "url", "token", "text"}) {
            assertFalse(columns.stream().anyMatch(c -> c.equals(forbidden) || c.endsWith("_" + forbidden)), forbidden);
        }
    }
}
