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

public record UpdateAppointmentRequest(@NotNull AppointmentType type, @NotNull Specialty specialty,
                                       @NotNull @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime scheduledAt,
                                       @NotNull @Min(5) @Max(480) Integer durationMinutes,
                                       @Size(max = 255) String location, @Size(max = 255) String clinician) {
}
