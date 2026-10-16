package com.aftercare.aftercareapi.Services.jobs;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The days on which a follow-up expects a check-in: from the day after discharge and the day after enrolment (so never
 * for a day before the patient had the link) up to and including the end date.
 */
public final class ExpectedDays {

    /** What the jobs need of an ACTIVE follow-up. */
    public record Row(Long id, Long patientId, LocalDate dischargeDate, LocalDate endDate, Instant createdAt) {

        public LocalDate enrolDate(ZoneId zone) {
            return createdAt.atZone(zone).toLocalDate();
        }

        /** First expected day. */
        public LocalDate firstExpected(ZoneId zone) {
            LocalDate a = dischargeDate.plusDays(1);
            LocalDate b = enrolDate(zone).plusDays(1);
            return a.isAfter(b) ? a : b;
        }

        public boolean isExpected(LocalDate day, ZoneId zone) {
            return !day.isBefore(firstExpected(zone)) && !day.isAfter(endDate);
        }
    }

    private ExpectedDays() {
    }
}
