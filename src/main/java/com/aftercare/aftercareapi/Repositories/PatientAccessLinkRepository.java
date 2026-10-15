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

    /** Non-revoked links whose follow-up or appointment belongs to the patient (targets fetched; usability is checked by the caller). */
    @Query("select l from PatientAccessLink l left join fetch l.followUp f left join fetch l.appointment a "
            + "where l.revokedAt is null and (f.patient.id = :patientId or a.patient.id = :patientId)")
    List<PatientAccessLink> findActiveForPatient(@org.springframework.data.repository.query.Param("patientId") Long patientId);

    @Query("select l from PatientAccessLink l left join fetch l.followUp f left join fetch l.appointment a "
            + "where l.revokedAt is null and (f.patient.id in :patientIds or a.patient.id in :patientIds)")
    List<PatientAccessLink> findActiveForPatients(@org.springframework.data.repository.query.Param("patientIds") Collection<Long> patientIds);
}
