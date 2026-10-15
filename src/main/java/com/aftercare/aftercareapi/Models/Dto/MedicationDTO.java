package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;
import java.time.LocalDate;

public record MedicationDTO(Long id, String name, String dose, String frequency, LocalDate startDate, Instant createdAt, String createdByName, Instant updatedAt, String updatedByName) {
}
