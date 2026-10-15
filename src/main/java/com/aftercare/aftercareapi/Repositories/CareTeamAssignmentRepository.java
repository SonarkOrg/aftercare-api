package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface CareTeamAssignmentRepository extends JpaRepository<CareTeamAssignment, Long> {

    @Query("select c from CareTeamAssignment c join fetch c.staff where c.patient.id = :patientId")
    List<CareTeamAssignment> findByPatientIdWithStaff(@Param("patientId") Long patientId);

    Optional<CareTeamAssignment> findByIdAndPatientId(Long id, Long patientId);

    boolean existsByPatientIdAndStaffId(Long patientId, Long staffId);

    boolean existsByPatientId(Long patientId);
}
