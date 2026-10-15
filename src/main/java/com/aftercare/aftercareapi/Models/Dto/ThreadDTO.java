package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;
import java.util.List;

public record ThreadDTO(Long patientId, boolean messagingOpen, Instant messagingOpenUntil, List<MessageDTO> items, boolean hasMore) {
}
