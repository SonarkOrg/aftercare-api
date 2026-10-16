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

    /** The not-yet-resolved missed check-in alert of one follow-up day, if any. */
    List<Alert> findByFollowUpIdAndTypeAndMissedDateAndStatusNot(Long followUpId, AlertType type, java.time.LocalDate missedDate,
                                                                 AlertStatus status);

    /** (followUpId, missedDate) of existing MISSED_CHECKIN alerts in a date range, for idempotency. */
    @Query("select a.followUp.id, a.missedDate from Alert a where a.type = com.aftercare.aftercareapi.Enums.AlertType.MISSED_CHECKIN "
            + "and a.followUp.id in :ids and a.missedDate between :from and :to")
    List<Object[]> missedKeys(@org.springframework.data.repository.query.Param("ids") Collection<Long> ids,
                              @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
                              @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);

    @Query("select a.patient.id, count(a) from Alert a where a.status in (com.aftercare.aftercareapi.Enums.AlertStatus.OPEN, com.aftercare.aftercareapi.Enums.AlertStatus.ACKNOWLEDGED) group by a.patient.id")
    List<Object[]> countUnresolvedByPatient();
}
