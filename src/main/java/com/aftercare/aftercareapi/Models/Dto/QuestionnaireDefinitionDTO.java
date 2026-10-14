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

public record QuestionnaireDefinitionDTO(Long id, String url, String version, LocalizedText title,
                                         QuestionnairePurpose purpose, Specialty specialty,
                                         List<AppointmentType> appointmentTypes, QuestionnaireStatus status,
                                         Instant loadedAt) {

    public static QuestionnaireDefinitionDTO from(QuestionnaireDefinition d) {
        return new QuestionnaireDefinitionDTO(d.getId(), d.getUrl(), d.getVersion(),
                new LocalizedText(d.getTitleFr(), d.getTitleEn()), d.getPurpose(), d.getSpecialty(),
                d.getAppointmentTypes().stream().sorted().toList(), d.getStatus(), d.getLoadedAt());
    }
}
