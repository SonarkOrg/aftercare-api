package com.aftercare.aftercareapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalTime;

/** {@code aftercare.notifications.*}. Credentials never appear in {@link #toString()}. */
@ConfigurationProperties("aftercare.notifications")
public record NotificationProperties(
        @DefaultValue Email email,
        @DefaultValue Sms sms,
        @DefaultValue("PT30S") Duration dispatchInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("300") int maxPerHour,
        @DefaultValue("4") int maxAutomatedPerPatientPerDay,
        @DefaultValue("3") int maxManualPerTargetPerDay,
        @DefaultValue QuietHours quietHours) {

    public record Email(@DefaultValue("log") String provider, @DefaultValue("") String from) {
        @Override
        public String toString() {
            return "Email[provider=" + provider + "]";
        }
    }

    public record Sms(@DefaultValue("log") String provider, @DefaultValue Twilio twilio) {
        @Override
        public String toString() {
            return "Sms[provider=" + provider + "]";
        }
    }

    public record Twilio(@DefaultValue("") String accountSid, @DefaultValue("") String authToken,
                         @DefaultValue("") String fromNumber, @DefaultValue("https://api.twilio.com") String baseUrl) {
        @Override
        public String toString() {
            return "Twilio[baseUrl=" + baseUrl + "]";
        }
    }

    public record QuietHours(@DefaultValue("21:00") String start, @DefaultValue("08:00") String end) {
        public LocalTime startTime() {
            return LocalTime.parse(start);
        }

        public LocalTime endTime() {
            return LocalTime.parse(end);
        }
    }

    @Override
    public String toString() {
        return "NotificationProperties[email=" + email + ", sms=" + sms + "]";
    }
}
