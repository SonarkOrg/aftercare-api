package com.aftercare.aftercareapi.Services.jobs;

import com.aftercare.aftercareapi.Models.JobLock;
import com.aftercare.aftercareapi.Repositories.JobLockRepository;
import com.aftercare.aftercareapi.config.JobProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A portable lease per job name (a ShedLock-like row): {@code UPDATE ... WHERE locked_until <= now}. Works the same on
 * Postgres and H2. A lost lease only delays a run; the unique keys of each job make a duplicate run harmless.
 */
@Service
public class JobLockService {

    private static final Logger log = LoggerFactory.getLogger(JobLockService.class);

    private final JobLockRepository repository;
    private final Clock clock;
    private final String instance;
    private final TransactionTemplate tx;
    @PersistenceContext
    private EntityManager em;

    public JobLockService(JobLockRepository repository, Clock clock, JobProperties props, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.clock = clock;
        String id = props.instanceId() + "-" + UUID.randomUUID();
        this.instance = id.length() > 100 ? id.substring(id.length() - 100) : id;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** True when this instance now holds the lease for {@code lease}. */
    public boolean tryAcquire(String name, Duration lease) {
        Instant now = Instant.now(clock);
        Instant until = now.plus(lease);
        Integer updated = tx.execute(s -> repository.acquire(name, until, instance, now));
        if (updated != null && updated == 1) {
            return true;
        }
        if (Boolean.TRUE.equals(tx.execute(s -> repository.existsById(name)))) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(tx.execute(s -> {
                JobLock l = new JobLock();
                l.setName(name);
                l.setLockedUntil(until);
                l.setLockedBy(instance);
                l.setLastStartedAt(now);
                em.persist(l);
                em.flush();
                return true;
            }));
        } catch (RuntimeException raced) {
            // Another instance created the row first (duplicate key): it holds the lease.
            log.debug("Job lock {} created concurrently", name);
            return false;
        }
    }

    public void release(String name) {
        Instant now = Instant.now(clock);
        tx.executeWithoutResult(s -> repository.release(name, instance, now));
    }
}
