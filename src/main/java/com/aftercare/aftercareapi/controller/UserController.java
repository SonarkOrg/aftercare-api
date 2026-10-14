package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.PatientDTO;
import com.aftercare.aftercareapi.Services.PatientService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class UserController {

    private final PatientService patientService;

    public UserController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping(value = "api/patients/register")
    public ResponseEntity<PatientDTO> addPatient(@Valid @RequestBody PatientDTO patientDTO) {
        return new ResponseEntity<>(patientService.addPatient(patientDTO), HttpStatus.CREATED);
    }

    @GetMapping(value = "api/patients")
    public ResponseEntity<List<PatientDTO>> getPatientList() {
        return ResponseEntity.ok(patientService.getPatients());
    }

    @GetMapping(value = "api/patients/{patientId}")
    public ResponseEntity<PatientDTO> getPatient(@PathVariable Long patientId) {
        return ResponseEntity.ok(patientService.getPatientById(patientId));
    }

    @DeleteMapping(value = "api/patients/{patientId}")
    public ResponseEntity<Void> deletePatient(@PathVariable Long patientId) {
        patientService.deletePatientById(patientId);
        return ResponseEntity.noContent().build();
    }

}
