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

    List<CheckIn> findByFollowUpPatientIdOrderByCheckInDateDescIdDesc(Long patientId);

    @EntityGraph(attributePaths = {"followUp", "followUp.patient", "questionnaire"})
    List<CheckIn> findByCheckInDateBetweenOrderByCheckInDateAscIdAsc(LocalDate from, LocalDate to);

}
