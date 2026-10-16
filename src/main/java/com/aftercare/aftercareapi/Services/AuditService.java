package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.AuditEvent;
import com.aftercare.aftercareapi.Models.Dto.AuditEventDTO;
import com.aftercare.aftercareapi.Models.Dto.PageResponse;
import com.aftercare.aftercareapi.Repositories.AuditEventRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Append-only access log. Details never carry PHI values: only field names, categories, counts and reveal reasons.
 * Changes commit with the change itself; views commit in their own transaction so they also work from read-only methods.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final PatientRepository patientRepository;
    private final Clock clock;
    @PersistenceContext
    private EntityManager em;

    public AuditService(AuditEventRepository repository, PatientRepository patientRepository, Clock clock) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.clock = clock;
    }

    /** Same transaction as the change it describes; fails when called without one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChange(Actor actor, AuditAction action, Long patientId, AuditResource resource, Long resourceId, String detail) {
        append(actor, action, patientId, resource, resourceId, detail);
    }

    /** Own transaction. Call it after the read succeeded so a 404 is never audited as a view. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccess(Actor actor, AuditAction action, Long patientId, AuditResource resource, Long resourceId, String detail) {
        append(actor, action, patientId, resource, resourceId, detail);
    }

    private void append(Actor actor, AuditAction action, Long patientId, AuditResource resource, Long resourceId, String detail) {
        String d = detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail);
        repository.save(new AuditEvent(Instant.now(clock), actor.sub(), actor.name(), action, patientId, resource, resourceId, d));
    }

    /** One patient's log, newest first, optionally limited to one actor (exact subject). */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventDTO> list(Long patientId, String actorSub, int page, int size) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        return query(patientId, actorSub, null, null, null, page, size);
    }

    /** The clinic-wide log, newest first. Every filter is optional; {@code from} is inclusive, {@code to} exclusive. */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventDTO> listAll(String actorSub, AuditAction action, Long patientId, Instant from, Instant to,
                                               int page, int size) {
        return query(patientId, actorSub, action, from, to, page, size);
    }

    private PageResponse<AuditEventDTO> query(Long patientId, String actorSub, AuditAction action, Instant from, Instant to,
                                              int page, int size) {
        StringBuilder jpql = new StringBuilder("select e from AuditEvent e where 1 = 1");
        if (patientId != null) jpql.append(" and e.patientId = :patientId");
        if (actorSub != null) jpql.append(" and e.actorSub = :actorSub");
        if (action != null) jpql.append(" and e.action = :action");
        if (from != null) jpql.append(" and e.occurredAt >= :from");
        if (to != null) jpql.append(" and e.occurredAt < :to");
        jpql.append(" order by e.occurredAt desc, e.id desc");
        TypedQuery<AuditEvent> q = em.createQuery(jpql.toString(), AuditEvent.class);
        if (patientId != null) q.setParameter("patientId", patientId);
        if (actorSub != null) q.setParameter("actorSub", actorSub);
        if (action != null) q.setParameter("action", action);
        if (from != null) q.setParameter("from", from);
        if (to != null) q.setParameter("to", to);
        q.setFirstResult(page * size);
        q.setMaxResults(size + 1);
        List<AuditEvent> rows = q.getResultList();
        boolean hasMore = rows.size() > size;
        return new PageResponse<>(rows.stream().limit(size)
                .map(e -> new AuditEventDTO(e.getId(), e.getOccurredAt(), e.getActorName(), e.getActorSub(), e.getAction(),
                        e.getResourceType(), e.getResourceId(), e.getPatientId(), e.getDetail()))
                .toList(), page, size, hasMore);
    }
}
