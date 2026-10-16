package com.aftercare.aftercareapi.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fails startup (with a message that never contains a secret) when a real provider is selected without what it needs,
 * so a misconfiguration is caught at boot rather than at 18:00 when reminders go out.
 */
@Component
public class NotificationConfigGuard implements InitializingBean {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final NotificationProperties props;
    private final Environment env;
    private final String frontendBaseUrl;

    public NotificationConfigGuard(NotificationProperties props, Environment env,
                                   @Value("${aftercare.frontend-base-url:}") String frontendBaseUrl) {
        this.props = props;
        this.env = env;
        this.frontendBaseUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.trim();
    }

    @Override
    public void afterPropertiesSet() {
        String email = lower(props.email().provider());
        String sms = lower(props.sms().provider());
        if (!email.equals("log") && !email.equals("smtp")) {
            throw new IllegalStateException("Unknown aftercare.notifications.email.provider (expected log or smtp)");
        }
        if (!sms.equals("log") && !sms.equals("twilio")) {
            throw new IllegalStateException("Unknown aftercare.notifications.sms.provider (expected log or twilio)");
        }
        if (email.equals("smtp")) {
            if (blank(env.getProperty("spring.mail.host"))) {
                throw new IllegalStateException("EMAIL_PROVIDER=smtp requires SMTP_HOST (spring.mail.host)");
            }
            if (blank(props.email().from())) {
                throw new IllegalStateException("EMAIL_PROVIDER=smtp requires EMAIL_FROM");
            }
        }
        if (sms.equals("twilio")) {
            NotificationProperties.Twilio t = props.sms().twilio();
            if (blank(t.accountSid()) || blank(t.authToken()) || blank(t.fromNumber())) {
                throw new IllegalStateException(
                        "SMS_PROVIDER=twilio requires TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM_NUMBER");
            }
            if (!E164.matcher(t.fromNumber().trim()).matches()) {
                throw new IllegalStateException("TWILIO_FROM_NUMBER must be in E.164 format (for example +15145550100)");
            }
        }
        if (!(email.equals("log") && sms.equals("log"))) {
            boolean localhost = env.acceptsProfiles(Profiles.of("local")) && frontendBaseUrl.startsWith("http://localhost");
            if (blank(frontendBaseUrl) || (!frontendBaseUrl.startsWith("https://") && !localhost)) {
                throw new IllegalStateException(
                        "A real notification provider requires FRONTEND_BASE_URL starting with https://");
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
