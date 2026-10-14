package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByStatusIn(Collection<AlertStatus> statuses);

    List<Alert> findByPatientId(Long patientId);

    List<Alert> findByCheckInId(Long checkInId);

    @Query("select a.patient.id, count(a) from Alert a where a.status in (com.aftercare.aftercareapi.Enums.AlertStatus.OPEN, com.aftercare.aftercareapi.Enums.AlertStatus.ACKNOWLEDGED) group by a.patient.id")
    List<Object[]> countUnresolvedByPatient();
}
