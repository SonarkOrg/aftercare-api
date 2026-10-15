package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.Dto.PatientSummaryDTO;
import com.aftercare.aftercareapi.Models.PatientDTO;
import com.aftercare.aftercareapi.Services.ActorResolver;
import com.aftercare.aftercareapi.Services.PatientService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class UserController {

    private final PatientService patientService;
    private final ActorResolver actors;

    public UserController(PatientService patientService, ActorResolver actors) {
        this.patientService = patientService;
        this.actors = actors;
    }

    @PostMapping(value = "api/patients/register")
    public ResponseEntity<PatientDTO> addPatient(@Valid @RequestBody PatientDTO patientDTO) {
        return new ResponseEntity<>(patientService.addPatient(patientDTO), HttpStatus.CREATED);
    }

    @GetMapping(value = "api/patients")
    public ResponseEntity<List<PatientSummaryDTO>> getPatientList() {
        return ResponseEntity.ok(patientService.getPatientSummaries());
    }

    @GetMapping(value = "api/patients/{patientId}")
    public ResponseEntity<PatientDTO> getPatient(@PathVariable Long patientId) {
        return ResponseEntity.ok(patientService.getPatientById(patientId));
    }

    @DeleteMapping(value = "api/patients/{patientId}")
    public ResponseEntity<Void> deletePatient(@PathVariable Long patientId, @AuthenticationPrincipal Jwt jwt) {
        patientService.deletePatientById(patientId, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }

}
