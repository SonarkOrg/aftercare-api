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

public record QuestionnaireDefinitionDetailDTO(Long id, String url, String version, LocalizedText title,
                                               QuestionnairePurpose purpose, Specialty specialty,
                                               List<AppointmentType> appointmentTypes, QuestionnaireStatus status,
                                               Instant loadedAt, JsonNode questionnaire, JsonNode rules) {

    public static QuestionnaireDefinitionDetailDTO from(QuestionnaireDefinitionDTO b, JsonNode questionnaire, JsonNode rules) {
        return new QuestionnaireDefinitionDetailDTO(b.id(), b.url(), b.version(), b.title(), b.purpose(), b.specialty(),
                b.appointmentTypes(), b.status(), b.loadedAt(), questionnaire, rules);
    }
}
