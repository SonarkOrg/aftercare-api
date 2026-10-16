package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {
    boolean existsByPatientId(Long patientId);

    List<FollowUp> findByPatientIdAndStatus(Long patientId, FollowUpStatus status);

    List<FollowUp> findByStatus(FollowUpStatus status);

    /** ACTIVE follow-ups that can still have an expected day on or after {@code from}; projection only. */
    @Query("select new com.aftercare.aftercareapi.Services.jobs.ExpectedDays$Row(f.id, f.patient.id, f.dischargeDate, f.endDate, f.createdAt) "
            + "from FollowUp f where f.status = com.aftercare.aftercareapi.Enums.FollowUpStatus.ACTIVE and f.endDate >= :from")
    List<com.aftercare.aftercareapi.Services.jobs.ExpectedDays.Row> activeEndingOnOrAfter(@Param("from") LocalDate from);

    List<FollowUp> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from FollowUp x where x.id = :id")
    Optional<FollowUp> findByIdForUpdate(@Param("id") Long id);
}
