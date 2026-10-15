package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface DiagnosisRepository extends JpaRepository<Diagnosis, Long> {

    List<Diagnosis> findByPatientIdAndDeletedAtIsNull(Long patientId);

    Optional<Diagnosis> findByIdAndPatientIdAndDeletedAtIsNull(Long id, Long patientId);

    boolean existsByPatientId(Long patientId);
}
