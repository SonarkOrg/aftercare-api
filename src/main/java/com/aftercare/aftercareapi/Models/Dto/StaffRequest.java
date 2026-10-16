package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.validation.constraints.NotNull;


public record StaffRequest(String displayName, String email, @NotNull StaffRole role, @NotNull AccessRole accessRole, String auth0Subject) {
}
