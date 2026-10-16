package com.aftercare.aftercareapi.Models.Dto;

import jakarta.validation.constraints.NotNull;

/** Times are "HH:mm" strings, parsed by the service so a bad value yields a field error on its own name. */
public record NotificationSettingsRequest(@NotNull Boolean emailEnabled, @NotNull Boolean smsEnabled,
                                          @NotNull Boolean checkInReminderEnabled, @NotNull String checkInReminderTime,
                                          @NotNull Boolean appointmentReminderEnabled, @NotNull Integer appointmentReminderHours,
                                          @NotNull Boolean missedCheckInEnabled, @NotNull String missedCheckInCutoff) {
}
