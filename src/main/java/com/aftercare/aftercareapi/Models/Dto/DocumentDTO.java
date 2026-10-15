package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record DocumentDTO(Long id, Long patientId, String title, DocumentCategory category, String contentType, long sizeBytes, String originalFilename, String sha256, Instant uploadedAt, String uploadedByName) {
}
