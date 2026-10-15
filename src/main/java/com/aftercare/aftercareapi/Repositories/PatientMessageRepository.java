package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface PatientMessageRepository extends JpaRepository<PatientMessage, Long> {

    boolean existsByPatientId(Long patientId);

    List<PatientMessage> findByPatientIdAndIdLessThanOrderByIdDesc(Long patientId, Long before, Pageable pageable);

    long countByPatientIdAndSenderAndCreatedAtAfter(Long patientId, MessageSender sender, Instant after);

    @Query("select count(m) from PatientMessage m where m.patient.id = :patientId and m.sender = com.aftercare.aftercareapi.Enums.MessageSender.STAFF and m.readByPatientAt is null")
    long countUnreadByPatient(@Param("patientId") Long patientId);

    @Query("select m from PatientMessage m join fetch m.patient where m.id in (select max(m2.id) from PatientMessage m2 group by m2.patient.id) order by m.createdAt desc, m.id desc")
    List<PatientMessage> findLatestPerPatient(Pageable pageable);

    @Query("select m.patient.id, count(m) from PatientMessage m where m.sender = com.aftercare.aftercareapi.Enums.MessageSender.PATIENT and m.readByStaffAt is null and m.patient.id in :ids group by m.patient.id")
    List<Object[]> unreadForStaffByPatient(@Param("ids") Collection<Long> ids);

    @Query("select count(m), count(distinct m.patient.id) from PatientMessage m where m.sender = com.aftercare.aftercareapi.Enums.MessageSender.PATIENT and m.readByStaffAt is null")
    List<Object[]> unreadForStaffTotals();

    @Modifying
    @Query("update PatientMessage m set m.readByStaffAt = :now, m.readByStaffSub = :sub where m.patient.id = :patientId and m.sender = com.aftercare.aftercareapi.Enums.MessageSender.PATIENT and m.id <= :upToId and m.readByStaffAt is null")
    int markReadByStaff(@Param("patientId") Long patientId, @Param("upToId") Long upToId, @Param("now") Instant now, @Param("sub") String sub);

    @Modifying
    @Query("update PatientMessage m set m.readByPatientAt = :now where m.patient.id = :patientId and m.sender = com.aftercare.aftercareapi.Enums.MessageSender.STAFF and m.id <= :upToId and m.readByPatientAt is null")
    int markReadByPatient(@Param("patientId") Long patientId, @Param("upToId") Long upToId, @Param("now") Instant now);
}
