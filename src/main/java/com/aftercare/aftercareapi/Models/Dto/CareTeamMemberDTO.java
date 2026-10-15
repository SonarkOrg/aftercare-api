package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record CareTeamMemberDTO(Long assignmentId, Long staffId, String displayName, StaffRole role, boolean active, Instant assignedAt, String assignedByName) {
}
