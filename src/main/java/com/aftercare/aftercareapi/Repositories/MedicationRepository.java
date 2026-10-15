package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface MedicationRepository extends JpaRepository<Medication, Long> {

    List<Medication> findByPatientIdAndDeletedAtIsNull(Long patientId);

    Optional<Medication> findByIdAndPatientIdAndDeletedAtIsNull(Long id, Long patientId);

    boolean existsByPatientId(Long patientId);
}
