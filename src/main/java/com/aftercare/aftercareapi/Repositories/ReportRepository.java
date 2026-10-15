package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import com.aftercare.aftercareapi.Models.Dto.ReportRows;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Projection-only queries for the reports summary. Never returns entities. */
public interface ReportRepository extends Repository<FollowUp, Long> {

    @Query("select new com.aftercare.aftercareapi.Models.Dto.ReportRows$FollowUpRow(f.id, f.dischargeDate, f.endDate, f.status, f.closedAt) "
            + "from FollowUp f where (f.dischargeDate <= :to and f.endDate >= :from) or (f.closedAt >= :start and f.closedAt < :end)")
    List<ReportRows.FollowUpRow> followUps(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                           @Param("start") Instant start, @Param("end") Instant end);

    @Query("select new com.aftercare.aftercareapi.Models.Dto.ReportRows$CheckInRow(c.followUp.id, c.checkInDate) "
            + "from CheckIn c where c.checkInDate >= :from and c.checkInDate <= :through")
    List<ReportRows.CheckInRow> checkIns(@Param("from") LocalDate from, @Param("through") LocalDate through);

    @Query("select new com.aftercare.aftercareapi.Models.Dto.ReportRows$AlertRow(a.severity, a.createdAt, a.acknowledgedAt, a.resolvedAt, a.status) "
            + "from Alert a where a.createdAt >= :start and a.createdAt < :end")
    List<ReportRows.AlertRow> alerts(@Param("start") Instant start, @Param("end") Instant end);

    @Query("select new com.aftercare.aftercareapi.Models.Dto.ReportRows$AppointmentRow(a.id, a.status) "
            + "from Appointment a where a.scheduledAt >= :start and a.scheduledAt < :end")
    List<ReportRows.AppointmentRow> appointments(@Param("start") Instant start, @Param("end") Instant end);

    @Query("select new com.aftercare.aftercareapi.Models.Dto.ReportRows$IntakeRow(i.appointment.id, size(i.flags)) "
            + "from AppointmentIntake i where i.appointment.scheduledAt >= :start and i.appointment.scheduledAt < :end")
    List<ReportRows.IntakeRow> intakes(@Param("start") Instant start, @Param("end") Instant end);

    @Query("select count(f) from FollowUp f where f.status = com.aftercare.aftercareapi.Enums.FollowUpStatus.ACTIVE and f.endDate >= :today")
    long activeFollowUps(@Param("today") LocalDate today);
}
