package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface PatientDocumentRepository extends JpaRepository<PatientDocument, Long> {

    List<PatientDocument> findByPatientIdAndDeletedAtIsNullOrderByUploadedAtDescIdDesc(Long patientId);

    Optional<PatientDocument> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByPatientId(Long patientId);
}
