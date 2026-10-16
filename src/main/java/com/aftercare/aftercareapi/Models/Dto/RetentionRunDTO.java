package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.RetentionMode;
import com.aftercare.aftercareapi.Enums.RetentionRunStatus;
import com.aftercare.aftercareapi.Enums.RetentionTrigger;

import java.time.Instant;

public record RetentionRunDTO(Long id, RetentionMode mode, RetentionTrigger trigger, RetentionRunStatus status,
                              Instant startedAt, Instant finishedAt, RetentionCounts counts, String actorName) {
}
