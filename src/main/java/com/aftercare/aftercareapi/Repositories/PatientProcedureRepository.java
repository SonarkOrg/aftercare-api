package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface PatientProcedureRepository extends JpaRepository<PatientProcedure, Long> {

    @Query("select p from PatientProcedure p left join fetch p.surgeon where p.patient.id = :patientId and p.deletedAt is null")
    List<PatientProcedure> findActiveWithSurgeon(@Param("patientId") Long patientId);

    Optional<PatientProcedure> findByIdAndPatientIdAndDeletedAtIsNull(Long id, Long patientId);

    boolean existsByPatientId(Long patientId);
}
