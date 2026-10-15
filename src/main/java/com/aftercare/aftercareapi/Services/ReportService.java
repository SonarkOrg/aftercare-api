package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.AlertStatus;
import com.aftercare.aftercareapi.Enums.AppointmentStatus;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Dto.ReportRows.*;
import com.aftercare.aftercareapi.Models.Dto.ReportSummaryDTO;
import com.aftercare.aftercareapi.Repositories.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Clinic report summary. At most six projection queries (no entities, no lazy associations), aggregated in Java with
 * LocalDate math in the business time zone.
 */
@Service
public class ReportService {

    private final ReportRepository repository;
    private final Clock clock;

    public ReportService(ReportRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** The inclusive day range of one follow-up in which a check-in is expected, clipped to the report; null when empty. */
    private record Expected(LocalDate lo, LocalDate hi) {
        long overlap(LocalDate from, LocalDate to) {
            LocalDate a = lo.isAfter(from) ? lo : from;
            LocalDate b = hi.isBefore(to) ? hi : to;
            return b.isBefore(a) ? 0 : ChronoUnit.DAYS.between(a, b) + 1;
        }

        boolean contains(LocalDate d) {
            return !d.isBefore(lo) && !d.isAfter(hi);
        }
    }

    @Transactional(readOnly = true)
    public ReportSummaryDTO summary(LocalDate fromParam, LocalDate toParam) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate to = toParam == null ? today : toParam;
        LocalDate from = fromParam == null ? to.minusDays(27) : fromParam;
        if (to.isBefore(from)) {
            throw ApiException.validation("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > 366) {
            throw ApiException.validation("to", "range must not exceed 366 days");
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        LocalDate through = to.isBefore(today.minusDays(1)) ? to : today.minusDays(1);

        // ---- follow-ups and check-ins
        List<FollowUpRow> followUps = repository.followUps(from, to, start, end);
        Map<Long, Expected> expected = new HashMap<>();
        long started = 0;
        long closed = 0;
        for (FollowUpRow f : followUps) {
            if (!f.dischargeDate().isBefore(from) && !f.dischargeDate().isAfter(to)) {
                started++;
            }
            if (f.closedAt() != null && !f.closedAt().isBefore(start) && f.closedAt().isBefore(end)) {
                closed++;
            }
            LocalDate lo = max(f.dischargeDate().plusDays(1), from);
            LocalDate hi = min(f.endDate(), through);
            if (f.closedAt() != null) {
                hi = min(hi, f.closedAt().atZone(zone).toLocalDate().minusDays(1));
            }
            if (!hi.isBefore(lo)) {
                expected.put(f.id(), new Expected(lo, hi));
            }
        }
        long expectedTotal = expected.values().stream().mapToLong(e -> e.overlap(from, to)).sum();
        List<CheckInRow> checkIns = through.isBefore(from) ? List.of() : repository.checkIns(from, through);
        List<LocalDate> receivedDates = new ArrayList<>();
        for (CheckInRow c : checkIns) {
            Expected e = expected.get(c.followUpId());
            if (e != null && e.contains(c.checkInDate())) {
                receivedDates.add(c.checkInDate());
            }
        }
        long received = receivedDates.size();

        // ---- alerts
        List<AlertRow> alertRows = repository.alerts(start, end);
        long high = alertRows.stream().filter(a -> a.severity() == AlertSeverity.HIGH).count();
        List<Duration> toRespond = new ArrayList<>();
        List<Duration> toResolve = new ArrayList<>();
        long unresolved = 0;
        for (AlertRow a : alertRows) {
            Instant responded = a.acknowledgedAt() != null ? a.acknowledgedAt() : a.resolvedAt();
            if (responded != null) {
                toRespond.add(Duration.between(a.createdAt(), responded));
            }
            if (a.resolvedAt() != null) {
                toResolve.add(Duration.between(a.createdAt(), a.resolvedAt()));
            }
            if (a.status() != AlertStatus.RESOLVED) {
                unresolved++;
            }
        }

        // ---- appointments
        List<AppointmentRow> appts = repository.appointments(start, end);
        Set<Long> cancelledIds = appts.stream().filter(a -> a.status() == AppointmentStatus.CANCELLED)
                .map(AppointmentRow::id).collect(Collectors.toSet());
        long total = appts.size();
        long cancelled = cancelledIds.size();
        long completed = appts.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED).count();
        long noShow = appts.stream().filter(a -> a.status() == AppointmentStatus.NO_SHOW).count();
        long qExpected = total - cancelled;
        long qCompleted = 0;
        long withFlags = 0;
        for (IntakeRow i : repository.intakes(start, end)) {
            if (!cancelledIds.contains(i.appointmentId())) {
                qCompleted++;
                if (i.flagCount() > 0) {
                    withFlags++;
                }
            }
        }

        // ---- weeks
        List<ReportSummaryDTO.Week> weeks = new ArrayList<>();
        for (LocalDate ws = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !ws.isAfter(to); ws = ws.plusDays(7)) {
            LocalDate wf = max(ws, from);
            LocalDate wt = min(ws.plusDays(6), to);
            long wExpected = expected.values().stream().mapToLong(e -> e.overlap(wf, wt)).sum();
            long wReceived = receivedDates.stream().filter(d -> !d.isBefore(wf) && !d.isAfter(wt)).count();
            long wHigh = 0;
            long wMedium = 0;
            for (AlertRow a : alertRows) {
                LocalDate d = a.createdAt().atZone(zone).toLocalDate();
                if (!d.isBefore(wf) && !d.isAfter(wt)) {
                    if (a.severity() == AlertSeverity.HIGH) wHigh++;
                    else wMedium++;
                }
            }
            weeks.add(new ReportSummaryDTO.Week(ws, wf, wt, wExpected, wReceived, wHigh, wMedium));
        }

        return new ReportSummaryDTO(from, to, Instant.now(clock),
                new ReportSummaryDTO.CheckIns(expectedTotal, received, rate(received, expectedTotal), through),
                new ReportSummaryDTO.FollowUps(repository.activeFollowUps(today), started, closed),
                new ReportSummaryDTO.Alerts(alertRows.size(), high, alertRows.size() - high, toRespond.size(), meanMinutes(toRespond),
                        toResolve.size(), meanMinutes(toResolve), unresolved),
                new ReportSummaryDTO.Appointments(total, completed, cancelled, noShow, qExpected, qCompleted,
                        rate(qCompleted, qExpected), withFlags, rate(withFlags, qCompleted), rate(cancelled, total),
                        rate(noShow, completed + noShow)),
                weeks);
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static Double meanMinutes(List<Duration> durations) {
        if (durations.isEmpty()) {
            return null;
        }
        double mean = durations.stream().mapToLong(Duration::getSeconds).average().orElse(0) / 60.0;
        return Math.round(mean * 10.0) / 10.0;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
