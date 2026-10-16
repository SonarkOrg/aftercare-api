package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.config.security.BucketMap;
import com.aftercare.aftercareapi.config.security.TokenBucket;
import com.aftercare.aftercareapi.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTests {

    private final MutableClock clock = new MutableClock(ZoneId.of("America/Toronto"));

    @Test
    void consumesUpToCapacityThenRefusesWithARetryAfter() {
        TokenBucket b = new TokenBucket(3, 60, clock);
        for (int i = 0; i < 3; i++) {
            assertTrue(b.tryConsume().allowed());
        }
        TokenBucket.Result r = b.tryConsume();
        assertFalse(r.allowed());
        assertEquals(1, r.retryAfterSeconds()); // 60 per minute: one token per second
    }

    @Test
    void refillIsContinuousAndCappedAtCapacity() {
        TokenBucket b = new TokenBucket(10, 60, clock);
        for (int i = 0; i < 10; i++) {
            b.tryConsume();
        }
        clock.plus(Duration.ofMillis(500));
        assertFalse(b.tryConsume().allowed(), "half a token is not enough");
        clock.plus(Duration.ofMillis(500));
        assertTrue(b.tryConsume().allowed());
        assertFalse(b.tryConsume().allowed());
        clock.plus(Duration.ofHours(1));
        for (int i = 0; i < 10; i++) {
            assertTrue(b.tryConsume().allowed(), "token " + i);
        }
        assertFalse(b.tryConsume().allowed(), "never more than the capacity");
    }

    @Test
    void retryAfterIsTheCeilingOfTheWaitWithAMinimumOfOne() {
        TokenBucket slow = new TokenBucket(1, 1, clock); // one token per minute
        assertTrue(slow.tryConsume().allowed());
        assertEquals(60, slow.tryConsume().retryAfterSeconds());
        clock.plus(Duration.ofSeconds(30));
        assertEquals(30, slow.tryConsume().retryAfterSeconds());
        clock.plus(Duration.ofSeconds(29).plusMillis(500));
        assertEquals(1, slow.tryConsume().retryAfterSeconds()); // 0.5 s rounds up, minimum 1
        clock.plus(Duration.ofSeconds(1));
        assertTrue(slow.tryConsume().allowed());
    }

    @Test
    void theFailureBucketConsumesWithoutBlockingAndPeeksEmpty() {
        TokenBucket failed = new TokenBucket(2, 1, clock);
        assertFalse(failed.peekEmpty());
        failed.consume();
        assertFalse(failed.peekEmpty());
        failed.consume();
        assertTrue(failed.peekEmpty());
        failed.consume(); // never goes below zero
        assertEquals(60, failed.retryAfterSeconds());
        clock.plus(Duration.ofSeconds(60));
        assertFalse(failed.peekEmpty());
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1, clock));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 0, clock));
    }

    @Test
    void bucketMapEvictsTheLeastRecentlyUsedKey() {
        BucketMap<String> map = new BucketMap<>(3, () -> new TokenBucket(1, 1, clock));
        map.get("a");
        map.get("b");
        map.get("c");
        map.get("a"); // a is now the most recent; b is the eldest
        map.get("d");
        assertEquals(3, map.size());
        assertTrue(map.contains("a"));
        assertFalse(map.contains("b"));
        assertTrue(map.contains("c"));
        assertTrue(map.contains("d"));
    }

    @Test
    void bucketMapReturnsTheSameBucketForAKey() {
        BucketMap<String> map = new BucketMap<>(10, () -> new TokenBucket(1, 1, clock));
        assertTrue(map.get("k").tryConsume().allowed());
        assertFalse(map.get("k").tryConsume().allowed());
        assertTrue(map.get("other").tryConsume().allowed());
    }

    @Test
    void bucketMapStaysBoundedUnderManyKeys() {
        BucketMap<Integer> map = new BucketMap<>(100, () -> new TokenBucket(1, 1, clock));
        for (int i = 0; i < 10_000; i++) {
            map.get(i);
        }
        assertEquals(100, map.size());
    }
}
