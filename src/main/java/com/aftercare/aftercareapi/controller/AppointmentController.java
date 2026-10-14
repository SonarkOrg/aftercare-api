package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.AppointmentStatus;
import com.aftercare.aftercareapi.Models.Dto.AppointmentDTO;
import com.aftercare.aftercareapi.Models.Dto.AppointmentDetailDTO;
import com.aftercare.aftercareapi.Models.Dto.AppointmentMetricsDTO;
import com.aftercare.aftercareapi.Models.Dto.AppointmentStatusRequest;
import com.aftercare.aftercareapi.Models.Dto.AppointmentWithCodeDTO;
import com.aftercare.aftercareapi.Models.Dto.CreateAppointmentRequest;
import com.aftercare.aftercareapi.Models.Dto.UpdateAppointmentRequest;
import com.aftercare.aftercareapi.Services.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping("/api/appointments")
    public ResponseEntity<AppointmentWithCodeDTO> create(@Valid @RequestBody CreateAppointmentRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(appointmentService.create(request, jwt.getSubject()));
    }

    @GetMapping("/api/appointments")
    public List<AppointmentDTO> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "status", required = false) List<AppointmentStatus> status) {
        return appointmentService.list(from, to, status);
    }

    @GetMapping("/api/appointments/metrics")
    public AppointmentMetricsDTO metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return appointmentService.metrics(from, to);
    }

    @GetMapping("/api/appointments/{id:\\d+}")
    public AppointmentDetailDTO detail(@PathVariable Long id) {
        return appointmentService.detail(id);
    }

    @PutMapping("/api/appointments/{id:\\d+}")
    public AppointmentDTO update(@PathVariable Long id, @Valid @RequestBody UpdateAppointmentRequest request) {
        return appointmentService.update(id, request);
    }

    @DeleteMapping("/api/appointments/{id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        appointmentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/appointments/{id:\\d+}/access-code")
    public AppointmentWithCodeDTO regenerate(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return appointmentService.regenerateCode(id, jwt.getSubject());
    }

    @PostMapping("/api/appointments/{id:\\d+}/review")
    public AppointmentDTO review(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return appointmentService.review(id, jwt.getSubject());
    }

    @PostMapping("/api/appointments/{id:\\d+}/status")
    public AppointmentDTO status(@PathVariable Long id, @Valid @RequestBody AppointmentStatusRequest request) {
        return appointmentService.changeStatus(id, request.status());
    }

    @GetMapping("/api/patients/{patientId:\\d+}/appointments")
    public List<AppointmentDTO> forPatient(@PathVariable Long patientId) {
        return appointmentService.listForPatient(patientId);
    }
}
