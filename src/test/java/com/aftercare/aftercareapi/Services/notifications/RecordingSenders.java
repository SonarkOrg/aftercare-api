package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Replaces the logging senders (by @Primary) with senders that record what they were given. */
@TestConfiguration
public class RecordingSenders {

    public static class Recording implements NotificationSender {
        private final NotificationChannel channel;
        public final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();
        public volatile Supplier<SendResult> behaviour = () -> new SendResult.Sent("rec-1");

        Recording(NotificationChannel channel) {
            this.channel = channel;
        }

        @Override
        public NotificationChannel channel() {
            return channel;
        }

        @Override
        public SendResult send(OutboundMessage m) {
            sent.add(m);
            return behaviour.get();
        }

        public void reset() {
            sent.clear();
            behaviour = () -> new SendResult.Sent("rec-1");
        }

        public List<OutboundMessage> messages() {
            return new ArrayList<>(sent);
        }
    }

    @Bean
    @Primary
    public Recording recordingEmailSender() {
        return new Recording(NotificationChannel.EMAIL);
    }

    @Bean
    @Primary
    public Recording recordingSmsSender() {
        return new Recording(NotificationChannel.SMS);
    }
}
