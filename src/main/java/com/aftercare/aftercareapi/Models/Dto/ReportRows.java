package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.AlertStatus;
import com.aftercare.aftercareapi.Enums.AppointmentStatus;
import com.aftercare.aftercareapi.Enums.FollowUpStatus;

import java.time.Instant;
import java.time.LocalDate;

/** Flat projections used by the reports query path (no entities, no lazy associations). */
public final class ReportRows {

    private ReportRows() {
    }

    public record FollowUpRow(Long id, LocalDate dischargeDate, LocalDate endDate, FollowUpStatus status, Instant closedAt) {
    }

    public record CheckInRow(Long followUpId, LocalDate checkInDate) {
    }

    public record AlertRow(AlertSeverity severity, Instant createdAt, Instant acknowledgedAt, Instant resolvedAt, AlertStatus status) {
    }

    public record AppointmentRow(Long id, AppointmentStatus status) {
    }

    public record IntakeRow(Long appointmentId, int flagCount) {
    }
}
