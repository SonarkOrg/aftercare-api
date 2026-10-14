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

public record AlertDTO(Long id, Long patientId, String patientFirstName, String patientLastName, Long followUpId,
                       Long checkInId, LocalDate checkInDate, String linkId, String questionLabel, String answerValue,
                       AlertSeverity severity, String message, String messageFr, AlertStatus status,
                       Instant createdAt, Instant acknowledgedAt, String acknowledgedBy,
                       Instant resolvedAt, String resolvedBy, String resolutionNote) {
}
