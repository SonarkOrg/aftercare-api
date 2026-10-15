package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ClinicSettingsRequest(String name, String addressLine, String city, String province, String postalCode, String phone, @NotNull Language defaultLanguage, @NotNull List<AlertSeverity> notifySeverities) {
}
