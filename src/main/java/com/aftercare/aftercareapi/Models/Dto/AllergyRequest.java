package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.validation.constraints.NotNull;


public record AllergyRequest(String substance, @NotNull AllergySeverity severity, String reaction) {
}
