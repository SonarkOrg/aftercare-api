package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record TimelineItemDTO(Long id, TimelineCategory category, ActivityKind kind, Instant occurredAt, ActorType actorType, String actorName, Long refId, AlertSeverity severity, String summary, CareNoteDTO note, String summaryFr) {
}
