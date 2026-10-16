package com.aftercare.aftercareapi.Services.jobs;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Models.Dto.RetentionCounts;
import com.aftercare.aftercareapi.Models.Dto.RetentionRunDTO;
import com.aftercare.aftercareapi.Models.RetentionRun;
import com.aftercare.aftercareapi.Repositories.RetentionRunRepository;
import com.aftercare.aftercareapi.Services.Actor;
import com.aftercare.aftercareapi.Services.AuditService;
import com.aftercare.aftercareapi.Services.ClinicSettingsService;
import com.aftercare.aftercareapi.Services.DocumentStorage;
import com.aftercare.aftercareapi.config.RetentionProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Data retention. Periods are set by an administrator (null keeps everything). A dry run only counts; a purge deletes in
 * batches, each in its own transaction. Every run is recorded with counts only, and audited. Audit rows are deleted only
 * by their own period.
 */
@Service
public class RetentionService {

    public static final String LOCK = "retention";
    public static final Actor SYSTEM = new Actor("system:retention", null);
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private static final String FU_COND = "((f.status = com.aftercare.aftercareapi.Enums.FollowUpStatus.CLOSED and f.closedAt < :c) "
            + "or (f.status = com.aftercare.aftercareapi.Enums.FollowUpStatus.ACTIVE and f.endDate < :d))";
    private static final String TERMINAL = "(com.aftercare.aftercareapi.Enums.NotificationStatus.SENT, "
            + "com.aftercare.aftercareapi.Enums.NotificationStatus.FAILED, "
            + "com.aftercare.aftercareapi.Enums.NotificationStatus.CANCELLED, "
            + "com.aftercare.aftercareapi.Enums.NotificationStatus.SKIPPED)";

    private final JobLockService locks;
    private final ClinicSettingsService settings;
    private final RetentionRunRepository runs;
    private final AuditService audit;
    private final DocumentStorage storage;
    private final RetentionProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    @PersistenceContext
    private EntityManager em;

    public RetentionService(JobLockService locks, ClinicSettingsService settings, RetentionRunRepository runs,
                            AuditService audit, DocumentStorage storage, RetentionProperties props, Clock clock,
                            PlatformTransactionManager txManager) {
        this.locks = locks;
        this.settings = settings;
        this.runs = runs;
        this.audit = audit;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Manual dry run (always allowed, whatever the purge flags say). 409 while another run holds the lock. */
    public RetentionRunDTO manualDryRun(Actor actor) {
        return run(RetentionMode.DRY_RUN, RetentionTrigger.MANUAL, actor);
    }

    public List<RetentionRunDTO> recentRuns() {
        return tx.execute(s -> runs.findAllByOrderByStartedAtDescIdDesc(PageRequest.of(0, 20)).stream()
                .map(RetentionService::toDto).toList());
    }

    /**
     * Runs once under the {@code retention} lease. A manual run that cannot get the lease is a 409; a scheduled one is
     * skipped (null).
     */
    public RetentionRunDTO run(RetentionMode mode, RetentionTrigger trigger, Actor actor) {
        if (!locks.tryAcquire(LOCK, Duration.ofHours(1))) {
            if (trigger == RetentionTrigger.MANUAL) {
                throw ApiException.invalidState("A retention run is already in progress");
            }
            return null;
        }
        try {
            return execute(mode, trigger, actor);
        } finally {
            locks.release(LOCK);
        }
    }

    private RetentionRunDTO execute(RetentionMode mode, RetentionTrigger trigger, Actor actor) {
        Instant startedAt = Instant.now(clock);
        Long runId = tx.execute(s -> {
            RetentionRun r = new RetentionRun();
            r.setMode(mode);
            r.setTrigger(trigger);
            r.setStatus(RetentionRunStatus.SUCCEEDED);
            r.setStartedAt(startedAt);
            r.setActorSub(actor.sub());
            r.setActorName(actor.name());
            return runs.saveAndFlush(r).getId();
        });
        RetentionCounts counts = RetentionCounts.ZERO;
        RetentionRunStatus status = RetentionRunStatus.SUCCEEDED;
        RuntimeException failure = null;
        try {
            ClinicSettings cfg = settings.current();
            counts = mode == RetentionMode.DRY_RUN ? count(cfg) : purge(cfg);
        } catch (RuntimeException e) {
            status = RetentionRunStatus.FAILED;
            failure = e;
            log.error("Retention run failed ({})", e.getClass().getSimpleName());
        }
        RetentionCounts finalCounts = counts;
        RetentionRunStatus finalStatus = status;
        RetentionRunDTO dto = tx.execute(s -> {
            RetentionRun r = runs.findById(runId).orElseThrow();
            r.setStatus(finalStatus);
            r.setFinishedAt(Instant.now(clock));
            r.setFollowUps(finalCounts.followUps());
            r.setCheckIns(finalCounts.checkIns());
            r.setAlerts(finalCounts.alerts());
            r.setDocuments(finalCounts.documents());
            r.setMessages(finalCounts.messages());
            r.setNotifications(finalCounts.notifications());
            r.setAuditEvents(finalCounts.auditEvents());
            return toDto(runs.saveAndFlush(r));
        });
        audit.recordAccess(actor, mode == RetentionMode.DRY_RUN ? AuditAction.RETENTION_DRY_RUN : AuditAction.RETENTION_PURGE,
                null, AuditResource.RETENTION, runId, finalCounts.detail());
        if (failure != null) {
            if (trigger == RetentionTrigger.MANUAL) {
                throw new IllegalStateException("Retention run failed");
            }
            log.warn("Scheduled retention run {} failed", runId);
        } else {
            log.info("Retention {} run {} finished: {}", mode, runId, finalCounts.detail());
        }
        return dto;
    }

    // ---- cutoffs

    private record Cutoffs(Instant followUp, LocalDate followUpDate, Instant document, Instant message, Instant notification,
                           Instant audit) {
    }

    private Cutoffs cutoffs(ClinicSettings cfg) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        Instant fu = at(now, cfg.getRetentionClosedFollowUpMonths());
        return new Cutoffs(fu, fu == null ? null : fu.atZone(clock.getZone()).toLocalDate().minusDays(1),
                at(now, cfg.getRetentionDeletedDocumentMonths()), at(now, cfg.getRetentionMessageMonths()),
                at(now, cfg.getRetentionNotificationMonths()), at(now, cfg.getRetentionAuditMonths()));
    }

    private static Instant at(ZonedDateTime now, Integer months) {
        return months == null ? null : now.minusMonths(months).toInstant();
    }

    // ---- dry run

    private RetentionCounts count(ClinicSettings cfg) {
        return tx.execute(s -> {
            Cutoffs c = cutoffs(cfg);
            long followUps = 0;
            long checkIns = 0;
            long alerts = 0;
            if (c.followUp() != null) {
                followUps = single(fu("select count(f) from FollowUp f where " + FU_COND, c));
                checkIns = single(fu("select count(x) from CheckIn x where x.followUp.id in (select f.id from FollowUp f where "
                        + FU_COND + ")", c));
                alerts = single(fu("select count(x) from Alert x where x.followUp.id in (select f.id from FollowUp f where "
                        + FU_COND + ")", c));
            }
            long documents = c.document() == null ? 0 : single(em.createQuery(
                    "select count(d) from PatientDocument d where d.deletedAt < :c", Long.class).setParameter("c", c.document()));
            long messages = c.message() == null ? 0 : single(em.createQuery(
                    "select count(m) from PatientMessage m where m.createdAt < :c", Long.class).setParameter("c", c.message()));
            long notifications = 0;
            if (c.notification() != null) {
                String exclude = c.followUp() == null ? "" : " and (n.followUpId is null or n.followUpId not in "
                        + "(select f.id from FollowUp f where " + FU_COND + "))";
                TypedQuery<Long> q = em.createQuery("select count(n) from NotificationOutbox n where n.createdAt < :nc "
                        + "and n.status in " + TERMINAL + exclude, Long.class).setParameter("nc", c.notification());
                if (c.followUp() != null) {
                    q.setParameter("c", c.followUp()).setParameter("d", c.followUpDate());
                }
                notifications = single(q);
            }
            long auditEvents = c.audit() == null ? 0 : single(em.createQuery(
                    "select count(e) from AuditEvent e where e.occurredAt < :c", Long.class).setParameter("c", c.audit()));
            return new RetentionCounts(followUps, checkIns, alerts, documents, messages, notifications, auditEvents);
        });
    }

    private TypedQuery<Long> fu(String jpql, Cutoffs c) {
        return em.createQuery(jpql, Long.class).setParameter("c", c.followUp()).setParameter("d", c.followUpDate());
    }

    private static long single(TypedQuery<Long> q) {
        Long v = q.getSingleResult();
        return v == null ? 0 : v;
    }

    // ---- purge

    private RetentionCounts purge(ClinicSettings cfg) {
        Cutoffs c = cutoffs(cfg);
        int batch = props.purge().batchSize();
        long followUps = 0;
        long checkIns = 0;
        long alerts = 0;
        if (c.followUp() != null) {
            long[] t = new long[3];
            long[] step;
            do {
                step = tx.execute(s -> purgeFollowUps(c, batch));
                t[0] += step[0];
                t[1] += step[1];
                t[2] += step[2];
            } while (step[0] > 0);
            followUps = t[0];
            checkIns = t[1];
            alerts = t[2];
        }
        long documents = 0;
        if (c.document() != null) {
            long n;
            do {
                n = tx.execute(s -> purgeDocuments(c.document(), batch));
                documents += n;
            } while (n > 0);
        }
        long messages = 0;
        if (c.message() != null) {
            long n;
            do {
                n = tx.execute(st -> purgeMessages(c.message(), batch));
                messages += n;
            } while (n > 0);
        }
        long notifications = c.notification() == null ? 0 : purgeSimple(
                "select n.id from NotificationOutbox n where n.createdAt < :c and n.status in " + TERMINAL + " order by n.id",
                "delete from NotificationOutbox n where n.id in :ids", c.notification(), batch);
        long auditEvents = 0;
        if (c.audit() != null) {
            long n;
            do {
                n = tx.execute(s -> purgeAudit(c.audit(), batch));
                auditEvents += n;
            } while (n > 0);
        }
        return new RetentionCounts(followUps, checkIns, alerts, documents, messages, notifications, auditEvents);
    }

    /** One batch of expired follow-ups with everything that hangs off them. Returns {followUps, checkIns, alerts}. */
    private long[] purgeFollowUps(Cutoffs c, int batch) {
        List<Long> ids = em.createQuery("select f.id from FollowUp f where " + FU_COND + " order by f.id", Long.class)
                .setParameter("c", c.followUp()).setParameter("d", c.followUpDate()).setMaxResults(batch).getResultList();
        if (ids.isEmpty()) {
            return new long[3];
        }
        List<Long> alertIds = em.createQuery("select a.id from Alert a where a.followUp.id in :ids", Long.class)
                .setParameter("ids", ids).getResultList();
        List<Long> checkInIds = em.createQuery("select x.id from CheckIn x where x.followUp.id in :ids", Long.class)
                .setParameter("ids", ids).getResultList();
        long alerts = em.createQuery("delete from Alert a where a.followUp.id in :ids").setParameter("ids", ids).executeUpdate();
        long checkIns = em.createQuery("delete from CheckIn x where x.followUp.id in :ids").setParameter("ids", ids).executeUpdate();
        em.createQuery("delete from PatientAccessLink l where l.followUp.id in :ids").setParameter("ids", ids).executeUpdate();
        em.createQuery("delete from NotificationOutbox n where n.followUpId in :ids").setParameter("ids", ids).executeUpdate();
        activity(List.of(ActivityKind.CHECK_IN_SUBMITTED), checkInIds);
        activity(List.of(ActivityKind.ALERT_CREATED, ActivityKind.ALERT_ACKNOWLEDGED, ActivityKind.ALERT_RESOLVED), alertIds);
        activity(List.of(ActivityKind.FOLLOW_UP_STARTED, ActivityKind.FOLLOW_UP_CLOSED), ids);
        long followUps = em.createQuery("delete from FollowUp f where f.id in :ids").setParameter("ids", ids).executeUpdate();
        em.flush();
        return new long[]{followUps, checkIns, alerts};
    }

    private long purgeDocuments(Instant cutoff, int batch) {
        List<Object[]> docs = em.createQuery("select d.id, d.storageKey from PatientDocument d where d.deletedAt < :c order by d.id",
                Object[].class).setParameter("c", cutoff).setMaxResults(batch).getResultList();
        if (docs.isEmpty()) {
            return 0;
        }
        List<Long> ids = docs.stream().map(o -> (Long) o[0]).toList();
        activity(List.of(ActivityKind.DOCUMENT_UPLOADED, ActivityKind.DOCUMENT_DELETED), ids);
        int n = em.createQuery("delete from PatientDocument d where d.id in :ids").setParameter("ids", ids).executeUpdate();
        em.flush();
        for (Object[] o : docs) {
            storage.delete((String) o[1]);
        }
        return n;
    }

    /** One batch of expired messages together with their timeline previews in patient_activity. */
    private long purgeMessages(Instant cutoff, int batch) {
        List<Long> ids = em.createQuery("select m.id from PatientMessage m where m.createdAt < :c order by m.id", Long.class)
                .setParameter("c", cutoff).setMaxResults(batch).getResultList();
        if (ids.isEmpty()) {
            return 0;
        }
        activity(List.of(ActivityKind.MESSAGE_FROM_PATIENT, ActivityKind.MESSAGE_TO_PATIENT), ids);
        int n = em.createQuery("delete from PatientMessage m where m.id in :ids").setParameter("ids", ids).executeUpdate();
        em.flush();
        return n;
    }

    private void activity(List<ActivityKind> kinds, List<Long> refIds) {
        if (refIds.isEmpty()) {
            return;
        }
        em.createQuery("delete from PatientActivity x where x.kind in :kinds and x.refId in :ids")
                .setParameter("kinds", kinds).setParameter("ids", refIds).executeUpdate();
    }

    private long purgeSimple(String selectIds, String delete, Instant cutoff, int batch) {
        long total = 0;
        long n;
        do {
            n = tx.execute(s -> {
                List<Long> ids = em.createQuery(selectIds, Long.class).setParameter("c", cutoff).setMaxResults(batch).getResultList();
                if (ids.isEmpty()) {
                    return 0L;
                }
                return (long) em.createQuery(delete).setParameter("ids", ids).executeUpdate();
            });
            total += n;
        } while (n > 0);
        return total;
    }

    /** Audit rows are immutable entities: select ids with JPQL, delete them natively. */
    private long purgeAudit(Instant cutoff, int batch) {
        List<Long> ids = em.createQuery("select e.id from AuditEvent e where e.occurredAt < :c order by e.id", Long.class)
                .setParameter("c", cutoff).setMaxResults(batch).getResultList();
        if (ids.isEmpty()) {
            return 0;
        }
        Query q = em.createNativeQuery("delete from audit_events where id in (:ids)");
        q.setParameter("ids", ids);
        return q.executeUpdate();
    }

    private static RetentionRunDTO toDto(RetentionRun r) {
        return new RetentionRunDTO(r.getId(), r.getMode(), r.getTrigger(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(),
                new RetentionCounts(r.getFollowUps(), r.getCheckIns(), r.getAlerts(), r.getDocuments(), r.getMessages(),
                        r.getNotifications(), r.getAuditEvents()), r.getActorName());
    }
}
