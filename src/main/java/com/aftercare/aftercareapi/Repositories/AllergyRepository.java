package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface AllergyRepository extends JpaRepository<Allergy, Long> {

    List<Allergy> findByPatientIdAndDeletedAtIsNull(Long patientId);

    Optional<Allergy> findByIdAndPatientIdAndDeletedAtIsNull(Long id, Long patientId);

    boolean existsByPatientId(Long patientId);
}
