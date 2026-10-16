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

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    boolean existsByPatientId(Long patientId);

    List<Appointment> findByScheduledAtGreaterThanEqualAndScheduledAtLessThan(Instant from, Instant to);

    List<Appointment> findByPatientId(Long patientId);

    /** (id, patientId, scheduledAt, createdAt) of appointments in the given statuses scheduled in (from, to]; projection only. */
    @Query("select a.id, a.patient.id, a.scheduledAt, a.createdAt from Appointment a "
            + "where a.status in :statuses and a.scheduledAt > :from and a.scheduledAt <= :to")
    List<Object[]> reminderCandidates(@Param("statuses") java.util.Collection<com.aftercare.aftercareapi.Enums.AppointmentStatus> statuses,
                                      @Param("from") Instant from, @Param("to") Instant to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select x from Appointment x where x.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);
}
