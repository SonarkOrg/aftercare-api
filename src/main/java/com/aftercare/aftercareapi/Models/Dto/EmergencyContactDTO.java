package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record EmergencyContactDTO(Long id, String name, String relationship, String phone, Language preferredLanguage, Instant createdAt, String createdByName, Instant updatedAt, String updatedByName) {
}
