package com.aftercare.aftercareapi.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aftercare.aftercareapi.config.security.RateLimitStartupCheck;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitStartupCheckTests {

    private static RateLimitProperties props(boolean enabled, List<String> proxies) {
        return new RateLimitProperties(enabled, proxies, 10000, null, null, null);
    }

    private static List<ILoggingEvent> warnings(RateLimitProperties p, MockEnvironment env) {
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitStartupCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RateLimitStartupCheck.check(p, env);
            return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void warnsWhenEnabledOutsideLocalWithoutTrustedProxies() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertEquals(1, warnings(props(true, List.of()), prod).size());
        assertEquals(1, warnings(props(true, List.of()), new MockEnvironment()).size(), "no profile at all");
    }

    @Test
    void quietForLocalDisabledOrConfiguredProxies() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertTrue(warnings(props(true, List.of()), local).isEmpty());
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertTrue(warnings(props(false, List.of()), prod).isEmpty());
        assertTrue(warnings(props(true, List.of("10.0.0.0/8")), prod).isEmpty());
    }
}
