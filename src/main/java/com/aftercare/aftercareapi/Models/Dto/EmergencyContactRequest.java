package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.validation.constraints.NotNull;


public record EmergencyContactRequest(String name, String relationship, String phone, @NotNull Language preferredLanguage) {
}
