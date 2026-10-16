package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.Language;
import jakarta.validation.constraints.NotNull;

/** The caller's own preferences. There is no id: it can only change the caller's row. */
public record MePreferencesRequest(@NotNull Language uiLanguage) {
}
