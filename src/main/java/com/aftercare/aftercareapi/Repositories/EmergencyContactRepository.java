package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, Long> {

    List<EmergencyContact> findByPatientIdOrderByIdAsc(Long patientId);

    Optional<EmergencyContact> findByIdAndPatientId(Long id, Long patientId);

    boolean existsByPatientId(Long patientId);
}
