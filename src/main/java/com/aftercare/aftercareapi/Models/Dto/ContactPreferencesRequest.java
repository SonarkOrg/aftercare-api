package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.ContactConsent;
import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.NotificationChannel;
import jakarta.validation.constraints.NotNull;

public record ContactPreferencesRequest(@NotNull NotificationChannel preferredChannel, Language language,
                                        @NotNull ContactConsent contactConsent) {
}
