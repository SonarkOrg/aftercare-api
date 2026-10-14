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

    List<FollowUp> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from FollowUp x where x.id = :id")
    Optional<FollowUp> findByIdForUpdate(@Param("id") Long id);
}
