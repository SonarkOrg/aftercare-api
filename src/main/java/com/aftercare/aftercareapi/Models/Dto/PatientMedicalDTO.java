package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.LocalDate;
import java.util.List;

public record PatientMedicalDTO(Long patientId, LocalDate dateOfBirth, String healthNumberMasked, List<AllergyDTO> allergies, List<MedicationDTO> medications, List<ProcedureDTO> procedures, List<DiagnosisDTO> diagnoses) {
}
