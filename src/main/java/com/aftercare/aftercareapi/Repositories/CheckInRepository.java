package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {
    boolean existsByFollowUpIdAndCheckInDate(Long followUpId, LocalDate checkInDate);

    /** (followUpId, checkInDate) pairs, projection only, for the missed check-in and reminder jobs. */
    @Query("select c.followUp.id, c.checkInDate from CheckIn c where c.followUp.id in :ids and c.checkInDate between :from and :to")
    List<Object[]> dates(@org.springframework.data.repository.query.Param("ids") Collection<Long> ids,
                         @org.springframework.data.repository.query.Param("from") LocalDate from,
                         @org.springframework.data.repository.query.Param("to") LocalDate to);

    List<CheckIn> findByFollowUpPatientIdOrderByCheckInDateDescIdDesc(Long patientId);

    @EntityGraph(attributePaths = {"followUp", "followUp.patient", "questionnaire"})
    List<CheckIn> findByCheckInDateBetweenOrderByCheckInDateAscIdAsc(LocalDate from, LocalDate to);

}
