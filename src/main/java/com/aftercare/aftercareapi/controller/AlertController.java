package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.AlertStatus;
import com.aftercare.aftercareapi.Models.Dto.AlertDTO;
import com.aftercare.aftercareapi.Models.Dto.ResolveAlertRequest;
import com.aftercare.aftercareapi.Services.AlertService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/api/alerts")
    public List<AlertDTO> list(@RequestParam(name = "status", required = false) List<AlertStatus> status) {
        return alertService.list(status);
    }

    @GetMapping("/api/patients/{patientId:\\d+}/alerts")
    public List<AlertDTO> forPatient(@PathVariable Long patientId) {
        return alertService.listForPatient(patientId);
    }

    @PostMapping("/api/alerts/{id:\\d+}/acknowledge")
    public AlertDTO acknowledge(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return alertService.acknowledge(id, jwt.getSubject());
    }

    @PostMapping("/api/alerts/{id:\\d+}/resolve")
    public AlertDTO resolve(@PathVariable Long id, @Valid @RequestBody ResolveAlertRequest request,
                            @AuthenticationPrincipal Jwt jwt) {
        return alertService.resolve(id, request.note(), jwt.getSubject());
    }
}
