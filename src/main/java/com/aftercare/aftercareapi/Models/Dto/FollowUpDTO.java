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

public record FollowUpDTO(Long id, Long patientId, Specialty specialty, Long questionnaireDefinitionId,
                          String questionnaireUrl, String questionnaireVersion, LocalDate dischargeDate,
                          int durationDays, LocalDate endDate, FollowUpDisplayStatus status,
                          Instant accessLinkIssuedAt, Instant createdAt, String createdBy,
                          Instant closedAt, String closedBy) {

    public static FollowUpDTO from(FollowUp f, FollowUpDisplayStatus status, Instant accessLinkIssuedAt) {
        QuestionnaireDefinition q = f.getQuestionnaire();
        return new FollowUpDTO(f.getId(), f.getPatient().getId(), f.getSpecialty(), q.getId(), q.getUrl(), q.getVersion(),
                f.getDischargeDate(), f.getDurationDays(), f.getEndDate(), status, accessLinkIssuedAt,
                f.getCreatedAt(), f.getCreatedBy(), f.getClosedAt(), f.getClosedBy());
    }
}
