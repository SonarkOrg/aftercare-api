package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record AllergyDTO(Long id, String substance, AllergySeverity severity, String reaction, Instant createdAt, String createdByName, Instant updatedAt, String updatedByName) {
}
