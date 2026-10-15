package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record CareNoteDTO(Long id, Long patientId, String body, Instant notedAt, Instant createdAt, Instant editedAt, String authorName, Instant editableUntil, boolean editableByMe, boolean deletableByMe, boolean deleted, Instant deletedAt, String deletedByName, String deleteReason) {
}
