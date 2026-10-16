package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.Dto.RetentionRunDTO;
import com.aftercare.aftercareapi.Services.ActorResolver;
import com.aftercare.aftercareapi.Services.jobs.RetentionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Retention dry runs and history. There is deliberately no manual real purge. */
@RestController
public class RetentionController {

    private final RetentionService retention;
    private final ActorResolver actors;

    public RetentionController(RetentionService retention, ActorResolver actors) {
        this.retention = retention;
        this.actors = actors;
    }

    @PostMapping("/api/retention/dry-run")
    public RetentionRunDTO dryRun(@AuthenticationPrincipal Jwt jwt) {
        return retention.manualDryRun(actors.from(jwt));
    }

    @GetMapping("/api/retention/runs")
    public List<RetentionRunDTO> runs() {
        return retention.recentRuns();
    }
}
