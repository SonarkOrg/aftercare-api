package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.validation.constraints.NotNull;


public record CareTeamAssignRequest(@NotNull Long staffId, StaffRole role) {
}
