package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record StaffDTO(Long id, String displayName, String email, StaffRole role, AccessRole accessRole, String auth0Subject, boolean active, Instant createdAt, Instant updatedAt) {
}
