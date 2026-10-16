package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default SMS sender (provider log): logs one redacted line and reports success. It does NOT deliver anything. */
@Component
@ConditionalOnProperty(name = "aftercare.notifications.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public SendResult send(OutboundMessage m) {
        log.info("Notification {} {} {} to {} delivered by logging sender (not sent)", m.outboxId(), m.channel(),
                m.template(), m.masked());
        return new SendResult.Sent("log-" + m.outboxId());
    }
}
