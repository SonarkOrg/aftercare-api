package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;

/**
 * One message handed to a sender. {@code outboxId}, {@code template} and {@code masked} exist only so senders can log a
 * redacted line. {@link #toString()} never prints the recipient or the body.
 */
public record OutboundMessage(NotificationChannel channel, String to, String subject, String body, long outboxId,
                              String template, String masked) {

    public OutboundMessage(NotificationChannel channel, String to, String subject, String body) {
        this(channel, to, subject, body, 0L, null, null);
    }

    @Override
    public String toString() {
        return "OutboundMessage[" + channel + "]";
    }
}
