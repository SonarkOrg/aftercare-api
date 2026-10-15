package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.AuditEvent;
import com.aftercare.aftercareapi.Models.Dto.AuditEventDTO;
import com.aftercare.aftercareapi.Models.Dto.PageResponse;
import com.aftercare.aftercareapi.Repositories.AuditEventRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Append-only access log. Details never carry PHI values: only field names, categories, counts and reveal reasons.
 * Changes commit with the change itself; views commit in their own transaction so they also work from read-only methods.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final PatientRepository patientRepository;
    private final Clock clock;

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

    @Transactional(readOnly = true)
    public PageResponse<AuditEventDTO> list(Long patientId, int page, int size) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        Slice<AuditEvent> slice = repository.findByPatientIdOrderByOccurredAtDescIdDesc(patientId, PageRequest.of(page, size));
        return new PageResponse<>(slice.getContent().stream()
                .map(e -> new AuditEventDTO(e.getId(), e.getOccurredAt(), e.getActorName(), e.getActorSub(), e.getAction(),
                        e.getResourceType(), e.getResourceId(), e.getDetail()))
                .toList(), page, size, slice.hasNext());
    }
}
