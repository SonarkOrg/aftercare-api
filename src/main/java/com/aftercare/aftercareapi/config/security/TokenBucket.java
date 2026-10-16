package com.aftercare.aftercareapi.config.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** A continuously refilling token bucket. Thread-safe. */
public final class TokenBucket {

    /** The outcome of {@link #tryConsume()}: when not allowed, how long to wait for the next token. */
    public record Result(boolean allowed, long retryAfterSeconds) {
    }

    private final int capacity;
    private final int refillPerMinute;
    private final Clock clock;
    private double tokens;
    private Instant last;

    public TokenBucket(int capacity, int refillPerMinute, Clock clock) {
        if (capacity < 1 || refillPerMinute < 1) {
            throw new IllegalArgumentException("capacity and refillPerMinute must be at least 1");
        }
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
        this.clock = clock;
        this.tokens = capacity;
        this.last = clock.instant();
    }

    /** Takes one token if there is one. */
    public synchronized Result tryConsume() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return new Result(true, 0);
        }
        return new Result(false, retryAfter());
    }

    /** True when less than one token is available (does not consume). */
    public synchronized boolean peekEmpty() {
        refill();
        return tokens < 1;
    }

    /** Takes one token without ever blocking (the failure bucket): the level never goes below zero. */
    public synchronized void consume() {
        refill();
        tokens = Math.max(0, tokens - 1);
    }

    public synchronized long retryAfterSeconds() {
        refill();
        return retryAfter();
    }

    private long retryAfter() {
        return Math.max(1, (long) Math.ceil((1 - tokens) * 60.0 / refillPerMinute));
    }

    private void refill() {
        Instant now = clock.instant();
        long elapsedNanos = Duration.between(last, now).toNanos();
        if (elapsedNanos > 0) {
            tokens = Math.min(capacity, tokens + elapsedNanos * refillPerMinute / 60e9);
            last = now;
        } else if (elapsedNanos < 0) {
            last = now;
        }
    }
}
