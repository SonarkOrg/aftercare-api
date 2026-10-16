package com.aftercare.aftercareapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class NotificationConfigGuardTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, NotificationConfigGuard.class);

    private static Throwable root(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private void fails(String expected, String... props) {
        runner.withPropertyValues(props).run(ctx -> {
            assertNotNull(ctx.getStartupFailure(), "expected startup to fail: " + String.join(",", props));
            String msg = root(ctx.getStartupFailure()).getMessage();
            assertTrue(msg.contains(expected), msg);
            for (String p : props) {
                String value = p.substring(p.indexOf('=') + 1);
                if (value.length() > 6 && !value.startsWith("http") && !value.equals("twilio")) {
                    assertFalse(msg.contains(value), "message must not echo configuration values: " + msg);
                }
            }
        });
    }

    private void starts(String... props) {
        runner.withPropertyValues(props).run(ctx -> assertNull(ctx.getStartupFailure(),
                ctx.getStartupFailure() == null ? "" : root(ctx.getStartupFailure()).getMessage()));
    }

    @Test
    void logProvidersNeedNothing() {
        starts();
        starts("aftercare.frontend-base-url=");
        starts("aftercare.notifications.email.provider=log", "aftercare.notifications.sms.provider=log", "aftercare.frontend-base-url=http://whatever");
    }

    @Test
    void smtpNeedsAHostAndAFromAddress() {
        fails("SMTP_HOST", "aftercare.notifications.email.provider=smtp", "aftercare.notifications.email.from=Clinic <a@b.ca>",
                "aftercare.frontend-base-url=https://app.example.ca");
        fails("SMTP_HOST", "aftercare.notifications.email.provider=smtp", "spring.mail.host=", "aftercare.notifications.email.from=a@b.ca",
                "aftercare.frontend-base-url=https://app.example.ca");
        fails("EMAIL_FROM", "aftercare.notifications.email.provider=smtp", "spring.mail.host=smtp.example.ca",
                "aftercare.frontend-base-url=https://app.example.ca");
        starts("aftercare.notifications.email.provider=smtp", "spring.mail.host=smtp.example.ca",
                "aftercare.notifications.email.from=Clinic <a@b.ca>", "aftercare.frontend-base-url=https://app.example.ca");
    }

    @Test
    void twilioNeedsAllCredentialsAndAnE164Number() {
        String https = "aftercare.frontend-base-url=https://app.example.ca";
        fails("TWILIO", "aftercare.notifications.sms.provider=twilio", "aftercare.notifications.sms.twilio.account-sid=ACabc",
                "aftercare.notifications.sms.twilio.from-number=+15145550100", https);
        fails("TWILIO", "aftercare.notifications.sms.provider=twilio", "aftercare.notifications.sms.twilio.auth-token=tok",
                "aftercare.notifications.sms.twilio.from-number=+15145550100", https);
        fails("E.164", "aftercare.notifications.sms.provider=twilio", "aftercare.notifications.sms.twilio.account-sid=ACabc",
                "aftercare.notifications.sms.twilio.auth-token=tok", "aftercare.notifications.sms.twilio.from-number=514-555-0100", https);
        fails("E.164", "aftercare.notifications.sms.provider=twilio", "aftercare.notifications.sms.twilio.account-sid=ACabc",
                "aftercare.notifications.sms.twilio.auth-token=tok", "aftercare.notifications.sms.twilio.from-number=+0123456789", https);
        starts("aftercare.notifications.sms.provider=twilio", "aftercare.notifications.sms.twilio.account-sid=ACabc",
                "aftercare.notifications.sms.twilio.auth-token=tok", "aftercare.notifications.sms.twilio.from-number=+15145550100", https);
    }

    @Test
    void aRealProviderNeedsAnHttpsFrontendUrl() {
        String smtp = "aftercare.notifications.email.provider=smtp";
        String host = "spring.mail.host=smtp.example.ca";
        String from = "aftercare.notifications.email.from=a@b.ca";
        fails("FRONTEND_BASE_URL", smtp, host, from);
        fails("FRONTEND_BASE_URL", smtp, host, from, "aftercare.frontend-base-url=");
        fails("FRONTEND_BASE_URL", smtp, host, from, "aftercare.frontend-base-url=http://app.example.ca");
        fails("FRONTEND_BASE_URL", smtp, host, from, "aftercare.frontend-base-url=http://localhost:4200");
    }

    @Test
    void localhostIsAllowedInTheLocalProfileOnly() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("aftercare.notifications.email.provider=smtp", "spring.mail.host=localhost",
                        "aftercare.notifications.email.from=a@b.ca", "aftercare.frontend-base-url=http://localhost:4200")
                .run(ctx -> assertNull(ctx.getStartupFailure()));
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("aftercare.notifications.email.provider=smtp", "spring.mail.host=localhost",
                        "aftercare.notifications.email.from=a@b.ca", "aftercare.frontend-base-url=http://app.example.ca")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test
    void unknownProvidersAreRejected() {
        fails("email.provider", "aftercare.notifications.email.provider=sendgrid");
        fails("sms.provider", "aftercare.notifications.sms.provider=vonage");
    }

    @Test
    void propertyRecordsNeverPrintCredentials() {
        runner.withPropertyValues("aftercare.notifications.sms.twilio.auth-token=SuperSecretToken",
                "aftercare.notifications.sms.twilio.account-sid=ACsecretsid", "aftercare.notifications.email.from=boss@clinic.example")
                .run(ctx -> {
                    String shown = ctx.getBean(NotificationProperties.class).toString();
                    assertFalse(shown.contains("SuperSecretToken"));
                    assertFalse(shown.contains("ACsecretsid"));
                    assertFalse(shown.contains("boss@clinic.example"));
                });
    }
}
