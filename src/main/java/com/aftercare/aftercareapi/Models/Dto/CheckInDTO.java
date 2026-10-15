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

public record CheckInDTO(Long id, Long patientId, Long followUpId, LocalDate checkInDate, Instant submittedAt,
                         Long questionnaireDefinitionId, String questionnaireUrl, String questionnaireVersion,
                         JsonNode response) {

    public static CheckInDTO from(CheckIn c, JsonNode response) {
        return new CheckInDTO(c.getId(), c.getFollowUp().getPatient().getId(), c.getFollowUp().getId(),
                c.getCheckInDate(), c.getSubmittedAt(), c.getQuestionnaire().getId(),
                c.getQuestionnaireUrl(), c.getQuestionnaireVersion(), response);
    }
}
