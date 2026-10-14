package com.aftercare.aftercareapi.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public class MutableClock extends Clock {
    public static final Instant DEFAULT = Instant.parse("2026-10-14T16:00:00Z");
    private volatile Instant now = DEFAULT;
    private final ZoneId zone;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void set(Instant i) {
        now = i;
    }

    public void plus(Duration d) {
        now = now.plus(d);
    }

    public void reset() {
        now = DEFAULT;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
        MutableClock c = new MutableClock(z);
        c.now = now;
        return c;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
