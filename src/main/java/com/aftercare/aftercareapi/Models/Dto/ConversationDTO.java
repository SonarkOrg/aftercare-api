package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record ConversationDTO(Long patientId, String patientFirstName, String patientLastName, Instant lastMessageAt, String lastMessagePreview, MessageSender lastSender, long unreadCount, boolean messagingOpen) {
}
