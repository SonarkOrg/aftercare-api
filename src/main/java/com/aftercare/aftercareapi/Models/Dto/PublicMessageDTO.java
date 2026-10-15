package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record PublicMessageDTO(Long id, MessageSender sender, String senderName, String body, Instant sentAt, boolean seen) {
}
