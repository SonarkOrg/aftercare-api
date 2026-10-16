package com.aftercare.aftercareapi.jobs;

import com.aftercare.aftercareapi.Services.jobs.JobLockService;
import com.aftercare.aftercareapi.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobLockServiceTests extends IntegrationTest {

    @Autowired
    JobLockService locks;

    @Test
    void acquireReleaseAcquire() {
        String name = "test-" + uniq();
        assertEquals(0, jdbc.queryForObject("select count(*) from job_locks where name = ?", Integer.class, name), "created on first use");
        assertTrue(locks.tryAcquire(name, Duration.ofMinutes(10)));
        assertEquals(1, jdbc.queryForObject("select count(*) from job_locks where name = ?", Integer.class, name));
        assertFalse(locks.tryAcquire(name, Duration.ofMinutes(10)));
        locks.release(name);
        Map<String, Object> row = jdbc.queryForMap("select locked_by, last_started_at, last_finished_at from job_locks where name = ?", name);
        assertNotNull(row.get("locked_by"));
        assertNotNull(row.get("last_started_at"));
        assertNotNull(row.get("last_finished_at"));
        assertTrue(locks.tryAcquire(name, Duration.ofMinutes(10)));
        locks.release(name);
    }

    @Test
    void anExpiredLeaseCanBeTaken() {
        String name = "test-" + uniq();
        assertTrue(locks.tryAcquire(name, Duration.ofMinutes(10)));
        clock.plus(Duration.ofMinutes(9));
        assertFalse(locks.tryAcquire(name, Duration.ofMinutes(10)));
        clock.plus(Duration.ofMinutes(2));
        assertTrue(locks.tryAcquire(name, Duration.ofMinutes(10)));
        locks.release(name);
    }

    @Test
    void releasingALockThatIsHeldByAnotherInstanceDoesNothing() {
        String name = "test-" + uniq();
        assertTrue(locks.tryAcquire(name, Duration.ofMinutes(10)));
        jdbc.update("update job_locks set locked_by = 'another-instance' where name = ?", name);
        locks.release(name);
        assertFalse(locks.tryAcquire(name, Duration.ofMinutes(10)), "still held by the other instance");
    }
}
