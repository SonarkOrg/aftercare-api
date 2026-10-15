package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Patient file: demographics update, medical information, RAMQ number, emergency contacts and care team. */
@RestController
public class PatientFileController {

    private final PatientService patientService;
    private final PatientFileService file;
    private final PatientContactService contacts;
    private final ActorResolver actors;
    private final AuditService audit;

    public PatientFileController(PatientService patientService, PatientFileService file, PatientContactService contacts,
                                 ActorResolver actors, AuditService audit) {
        this.patientService = patientService;
        this.file = file;
        this.contacts = contacts;
        this.actors = actors;
        this.audit = audit;
    }

    @PutMapping("/api/patients/{id:\\d+}")
    public com.aftercare.aftercareapi.Models.PatientDTO update(@PathVariable Long id,
                                                              @Valid @RequestBody com.aftercare.aftercareapi.Models.PatientDTO body,
                                                              @AuthenticationPrincipal Jwt jwt) {
        return patientService.update(id, body, actors.from(jwt));
    }

    @GetMapping("/api/patients/{id:\\d+}/medical")
    public PatientMedicalDTO medical(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        PatientMedicalDTO result = file.medical(id);
        audit.recordAccess(actors.from(jwt), AuditAction.PATIENT_FILE_VIEWED, id, AuditResource.PATIENT, id, null);
        return result;
    }

    // ---- health number

    @PutMapping("/api/patients/{id:\\d+}/health-number")
    public HealthNumberMaskedDTO setHealthNumber(@PathVariable Long id, @RequestBody HealthNumberRequest body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return file.setHealthNumber(id, body, actors.from(jwt));
    }

    @DeleteMapping("/api/patients/{id:\\d+}/health-number")
    public ResponseEntity<Void> clearHealthNumber(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        file.clearHealthNumber(id, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/patients/{id:\\d+}/health-number/reveal")
    public ResponseEntity<HealthNumberRevealDTO> reveal(@PathVariable Long id, @RequestBody RevealRequest body,
                                                        @AuthenticationPrincipal Jwt jwt) {
        PatientFileService.Revealed result = file.reveal(id, body);
        audit.recordAccess(actors.from(jwt), AuditAction.HEALTH_NUMBER_REVEALED, id, AuditResource.HEALTH_NUMBER, null,
                "reason: " + result.reason());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.dto());
    }

    // ---- allergies

    @PostMapping("/api/patients/{id:\\d+}/allergies")
    public ResponseEntity<AllergyDTO> addAllergy(@PathVariable Long id, @Valid @RequestBody AllergyRequest body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(file.addAllergy(id, body, actors.from(jwt)));
    }

    @PutMapping("/api/patients/{id:\\d+}/allergies/{itemId:\\d+}")
    public AllergyDTO updateAllergy(@PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody AllergyRequest body,
                                    @AuthenticationPrincipal Jwt jwt) {
        return file.updateAllergy(id, itemId, body, actors.from(jwt));
    }

    @DeleteMapping("/api/patients/{id:\\d+}/allergies/{itemId:\\d+}")
    public ResponseEntity<Void> removeAllergy(@PathVariable Long id, @PathVariable Long itemId, @AuthenticationPrincipal Jwt jwt) {
        file.removeAllergy(id, itemId, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- medications

    @PostMapping("/api/patients/{id:\\d+}/medications")
    public ResponseEntity<MedicationDTO> addMedication(@PathVariable Long id, @Valid @RequestBody MedicationRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(file.addMedication(id, body, actors.from(jwt)));
    }

    @PutMapping("/api/patients/{id:\\d+}/medications/{itemId:\\d+}")
    public MedicationDTO updateMedication(@PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody MedicationRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return file.updateMedication(id, itemId, body, actors.from(jwt));
    }

    @DeleteMapping("/api/patients/{id:\\d+}/medications/{itemId:\\d+}")
    public ResponseEntity<Void> removeMedication(@PathVariable Long id, @PathVariable Long itemId, @AuthenticationPrincipal Jwt jwt) {
        file.removeMedication(id, itemId, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- procedures

    @PostMapping("/api/patients/{id:\\d+}/procedures")
    public ResponseEntity<ProcedureDTO> addProcedure(@PathVariable Long id, @Valid @RequestBody ProcedureRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(file.addProcedure(id, body, actors.from(jwt)));
    }

    @PutMapping("/api/patients/{id:\\d+}/procedures/{itemId:\\d+}")
    public ProcedureDTO updateProcedure(@PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody ProcedureRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return file.updateProcedure(id, itemId, body, actors.from(jwt));
    }

    @DeleteMapping("/api/patients/{id:\\d+}/procedures/{itemId:\\d+}")
    public ResponseEntity<Void> removeProcedure(@PathVariable Long id, @PathVariable Long itemId, @AuthenticationPrincipal Jwt jwt) {
        file.removeProcedure(id, itemId, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- diagnoses

    @PostMapping("/api/patients/{id:\\d+}/diagnoses")
    public ResponseEntity<DiagnosisDTO> addDiagnosis(@PathVariable Long id, @Valid @RequestBody DiagnosisRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(file.addDiagnosis(id, body, actors.from(jwt)));
    }

    @PutMapping("/api/patients/{id:\\d+}/diagnoses/{itemId:\\d+}")
    public DiagnosisDTO updateDiagnosis(@PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody DiagnosisRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return file.updateDiagnosis(id, itemId, body, actors.from(jwt));
    }

    @DeleteMapping("/api/patients/{id:\\d+}/diagnoses/{itemId:\\d+}")
    public ResponseEntity<Void> removeDiagnosis(@PathVariable Long id, @PathVariable Long itemId, @AuthenticationPrincipal Jwt jwt) {
        file.removeDiagnosis(id, itemId, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- emergency contacts

    @GetMapping("/api/patients/{id:\\d+}/emergency-contacts")
    public List<EmergencyContactDTO> listContacts(@PathVariable Long id) {
        return contacts.listContacts(id);
    }

    @PostMapping("/api/patients/{id:\\d+}/emergency-contacts")
    public ResponseEntity<EmergencyContactDTO> addContact(@PathVariable Long id, @Valid @RequestBody EmergencyContactRequest body,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contacts.addContact(id, body, actors.from(jwt)));
    }

    @PutMapping("/api/patients/{id:\\d+}/emergency-contacts/{contactId:\\d+}")
    public EmergencyContactDTO updateContact(@PathVariable Long id, @PathVariable Long contactId,
                                             @Valid @RequestBody EmergencyContactRequest body, @AuthenticationPrincipal Jwt jwt) {
        return contacts.updateContact(id, contactId, body, actors.from(jwt));
    }

    @DeleteMapping("/api/patients/{id:\\d+}/emergency-contacts/{contactId:\\d+}")
    public ResponseEntity<Void> removeContact(@PathVariable Long id, @PathVariable Long contactId, @AuthenticationPrincipal Jwt jwt) {
        contacts.removeContact(id, contactId, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }

    // ---- care team

    @GetMapping("/api/patients/{id:\\d+}/care-team")
    public List<CareTeamMemberDTO> careTeam(@PathVariable Long id) {
        return contacts.listCareTeam(id);
    }

    @PostMapping("/api/patients/{id:\\d+}/care-team")
    public ResponseEntity<CareTeamMemberDTO> assign(@PathVariable Long id, @Valid @RequestBody CareTeamAssignRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contacts.assign(id, body, actors.from(jwt)));
    }

    @DeleteMapping("/api/patients/{id:\\d+}/care-team/{assignmentId:\\d+}")
    public ResponseEntity<Void> unassign(@PathVariable Long id, @PathVariable Long assignmentId, @AuthenticationPrincipal Jwt jwt) {
        contacts.unassign(id, assignmentId, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
