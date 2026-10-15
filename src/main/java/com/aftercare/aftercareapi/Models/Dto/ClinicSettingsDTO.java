package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;
import java.util.List;

public record ClinicSettingsDTO(String name, String addressLine, String city, String province, String postalCode, String phone, Language defaultLanguage, List<AlertSeverity> notifySeverities, Instant updatedAt, String updatedByName) {
}
