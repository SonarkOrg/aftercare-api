package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;

import java.util.*;

/** Deliberately not a CrudRepository: audit rows can be appended and read, never updated or deleted. */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    Slice<AuditEvent> findByPatientIdOrderByOccurredAtDescIdDesc(Long patientId, Pageable pageable);
}
