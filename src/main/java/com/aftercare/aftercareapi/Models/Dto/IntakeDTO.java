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

public record IntakeDTO(Instant submittedAt, Long questionnaireDefinitionId, String questionnaireUrl,
                        String questionnaireVersion, JsonNode response, List<Flag> flags) {

    public record Flag(String linkId, String label, String labelFr, String message, String messageFr) {
    }
}
