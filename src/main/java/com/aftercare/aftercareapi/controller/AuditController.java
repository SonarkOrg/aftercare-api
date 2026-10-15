package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;


/** Per-patient access log. Opening the first page is itself audited. */
@RestController
public class AuditController {

    private final AuditService audit;
    private final ActorResolver actors;

    public AuditController(AuditService audit, ActorResolver actors) {
        this.audit = audit;
        this.actors = actors;
    }

    @GetMapping("/api/patients/{id:\\d+}/audit")
    public PageResponse<AuditEventDTO> list(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size, @AuthenticationPrincipal Jwt jwt) {
        Paging.check(page, size);
        PageResponse<AuditEventDTO> result = audit.list(id, page, size);
        if (page == 0) {
            audit.recordAccess(actors.from(jwt), AuditAction.AUDIT_LOG_VIEWED, id, AuditResource.AUDIT_LOG, null, null);
        }
        return result;
    }
}
