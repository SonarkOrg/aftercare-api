package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReportSummaryDTO(LocalDate from, LocalDate to, Instant generatedAt, CheckIns checkIns, FollowUps followUps,
                               Alerts alerts, Appointments appointments, List<Week> weeks) {

    public record CheckIns(long expected, long received, Double completionRate, LocalDate throughDate) {
    }

    public record FollowUps(long activeNow, long started, long closed) {
    }

    public record Alerts(long total, long high, long medium, long responded, Double meanMinutesToAcknowledge,
                         long resolved, Double meanMinutesToResolve, long unresolved) {
    }

    public record Appointments(long total, long completed, long cancelled, long noShow, long questionnairesExpected,
                               long questionnairesCompleted, Double questionnaireCompletionRate, long intakesWithFlags,
                               Double needsReviewRate, Double cancellationRate, Double noShowRate) {
    }

    public record Week(LocalDate weekStart, LocalDate from, LocalDate to, long checkInsExpected, long checkInsReceived,
                       long alertsHigh, long alertsMedium) {
    }
}
