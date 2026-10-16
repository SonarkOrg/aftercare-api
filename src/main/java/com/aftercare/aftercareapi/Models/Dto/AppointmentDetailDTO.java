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

public record AppointmentDetailDTO(Long id, Long patientId, String patientFirstName, String patientLastName,
                                   AppointmentType type, Specialty specialty,
                                   Long questionnaireDefinitionId, String questionnaireUrl, String questionnaireVersion,
                                   @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime scheduledAt,
                                   int durationMinutes, String location, String clinician, AppointmentStatus status,
                                   boolean questionnaireMissing, Instant intakeSubmittedAt, int flagCount,
                                   Instant accessLinkIssuedAt, Instant reviewedAt, String reviewedBy,
                                   Instant createdAt, String createdBy, Instant updatedAt, IntakeDTO intake,
                                   boolean intakeRedacted) {

    public static AppointmentDetailDTO from(AppointmentDTO a, IntakeDTO intake, boolean intakeRedacted) {
        return new AppointmentDetailDTO(a.id(), a.patientId(), a.patientFirstName(), a.patientLastName(), a.type(),
                a.specialty(), a.questionnaireDefinitionId(), a.questionnaireUrl(), a.questionnaireVersion(),
                a.scheduledAt(), a.durationMinutes(), a.location(), a.clinician(), a.status(),
                a.questionnaireMissing(), a.intakeSubmittedAt(), a.flagCount(), a.accessLinkIssuedAt(),
                a.reviewedAt(), a.reviewedBy(), a.createdAt(), a.createdBy(), a.updatedAt(), intake, intakeRedacted);
    }
}
