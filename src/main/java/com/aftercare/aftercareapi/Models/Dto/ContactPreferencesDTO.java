package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.ContactConsent;
import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.NotificationChannel;

import java.time.Instant;

public record ContactPreferencesDTO(Long patientId, NotificationChannel preferredChannel, Language language,
                                    Language effectiveLanguage, ContactConsent contactConsent, Instant consentUpdatedAt,
                                    String consentUpdatedByName, boolean emailAvailable, boolean smsAvailable) {
}
