package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public interface PatientAccessLinkRepository extends JpaRepository<PatientAccessLink, Long> {
    Optional<PatientAccessLink> findByCodeHash(String codeHash);

    List<PatientAccessLink> findByFollowUpIdAndRevokedAtIsNull(Long followUpId);

    List<PatientAccessLink> findByAppointmentIdAndRevokedAtIsNull(Long appointmentId);

    List<PatientAccessLink> findByFollowUpIdInAndRevokedAtIsNull(Collection<Long> followUpIds);

    List<PatientAccessLink> findByAppointmentIdInAndRevokedAtIsNull(Collection<Long> appointmentIds);

    List<PatientAccessLink> findByAppointmentId(Long appointmentId);
}
