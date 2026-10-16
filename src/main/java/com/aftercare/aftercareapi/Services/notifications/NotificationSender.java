package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;

/** Delivers messages of one channel. Exactly one bean per channel is active. */
public interface NotificationSender {

    NotificationChannel channel();

    /** Never throws for provider errors; returns {@link SendResult.Transient} or {@link SendResult.Permanent}. */
    SendResult send(OutboundMessage message);
}
