package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record AuditEventDTO(Long id, Instant occurredAt, String actorName, String actorSub, AuditAction action, AuditResource resourceType, Long resourceId, Long patientId, String detail) {
}
