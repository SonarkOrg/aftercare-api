package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.LocalDate;

public record DiagnosisRequest(String description, String icd10Code, LocalDate diagnosedOn) {
}
