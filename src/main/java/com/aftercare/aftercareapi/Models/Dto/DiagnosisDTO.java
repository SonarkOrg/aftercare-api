package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;
import java.time.LocalDate;

public record DiagnosisDTO(Long id, String description, String icd10Code, LocalDate diagnosedOn, Instant createdAt, String createdByName, Instant updatedAt, String updatedByName) {
}
