package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record NotificationDTO(Long id, Long patientId, Long followUpId, Long appointmentId, NotificationTemplate template,
                              NotificationChannel channel, Language language, NotificationStatus status, boolean automated,
                              int attempts, NotificationError lastError, String recipientMasked, Instant createdAt,
                              Instant sentAt, String requestedByName) {
}
