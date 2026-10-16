package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.EmailProvider;
import com.aftercare.aftercareapi.Enums.SmsProvider;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalTime;

public record NotificationSettingsDTO(boolean emailEnabled, boolean smsEnabled, EmailProvider emailProvider,
                                      SmsProvider smsProvider, boolean checkInReminderEnabled,
                                      @JsonFormat(pattern = "HH:mm") LocalTime checkInReminderTime,
                                      boolean appointmentReminderEnabled, int appointmentReminderHours,
                                      boolean missedCheckInEnabled,
                                      @JsonFormat(pattern = "HH:mm") LocalTime missedCheckInCutoff,
                                      Instant updatedAt, String updatedByName) {
}
