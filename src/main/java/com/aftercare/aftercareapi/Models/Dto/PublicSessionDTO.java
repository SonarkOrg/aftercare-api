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

public record PublicSessionDTO(String kind, boolean submitted, JsonNode questionnaire, CheckInInfo checkIn,
                               AppointmentInfo appointment) {

    public record CheckInInfo(LocalDate checkInDate, LocalDate followUpEndDate) {
    }

    public record AppointmentInfo(AppointmentType type,
                                  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime scheduledAt,
                                  int durationMinutes, String location) {
    }
}
