package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Access log: per patient, and a clinic-wide view for administrators. Opening the first page is itself audited. */
@RestController
public class AuditController {

    private final AuditService audit;
    private final ActorResolver actors;
    private final Clock clock;

    public AuditController(AuditService audit, ActorResolver actors, Clock clock) {
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    @GetMapping("/api/patients/{id:\\d+}/audit")
    public PageResponse<AuditEventDTO> list(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size,
                                            @RequestParam(required = false) String actorSub,
                                            @AuthenticationPrincipal Jwt jwt) {
        Paging.check(page, size);
        String sub = checkActorSub(actorSub);
        PageResponse<AuditEventDTO> result = audit.list(id, sub, page, size);
        if (page == 0) {
            audit.recordAccess(actors.from(jwt), AuditAction.AUDIT_LOG_VIEWED, id, AuditResource.AUDIT_LOG, null, null);
        }
        return result;
    }

    @GetMapping("/api/audit")
    public PageResponse<AuditEventDTO> listAll(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size,
                                               @RequestParam(required = false) String actorSub,
                                               @RequestParam(required = false) AuditAction action,
                                               @RequestParam(required = false) Long patientId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @AuthenticationPrincipal Jwt jwt) {
        Paging.check(page, size);
        String sub = checkActorSub(actorSub);
        LocalDate today = LocalDate.now(clock);
        // Without from/to: the last 30 days (today included).
        LocalDate t = to != null ? to : today;
        LocalDate f = from != null ? from : t.minusDays(29);
        if (t.isBefore(f)) {
            throw ApiException.validation("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > 366) {
            throw ApiException.validation("to", "range must not exceed 366 days");
        }
        Instant start = f.atStartOfDay(clock.getZone()).toInstant();
        Instant end = t.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        PageResponse<AuditEventDTO> result = audit.listAll(sub, action, patientId, start, end, page, size);
        if (page == 0) {
            List<String> used = new ArrayList<>();
            if (sub != null) used.add("actorSub");
            if (action != null) used.add("action");
            if (patientId != null) used.add("patientId");
            if (from != null) used.add("from");
            if (to != null) used.add("to");
            audit.recordAccess(actors.from(jwt), AuditAction.AUDIT_LOG_VIEWED, null, AuditResource.AUDIT_LOG, null,
                    used.isEmpty() ? null : String.join(",", used));
        }
        return result;
    }

    private static String checkActorSub(String actorSub) {
        if (actorSub == null || actorSub.isBlank()) {
            return null;
        }
        if (actorSub.length() > 255) {
            throw ApiException.validation("actorSub", "must be at most 255 characters");
        }
        return actorSub;
    }
}
