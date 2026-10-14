package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AppointmentDTO(Long id, Long patientId, String patientFirstName, String patientLastName,
                             AppointmentType type, Specialty specialty,
                             Long questionnaireDefinitionId, String questionnaireUrl, String questionnaireVersion,
                             @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime scheduledAt,
                             int durationMinutes, String location, String clinician, AppointmentStatus status,
                             boolean questionnaireMissing, Instant intakeSubmittedAt, int flagCount,
                             Instant accessLinkIssuedAt, Instant reviewedAt, String reviewedBy,
                             Instant createdAt, String createdBy, Instant updatedAt) {
}
