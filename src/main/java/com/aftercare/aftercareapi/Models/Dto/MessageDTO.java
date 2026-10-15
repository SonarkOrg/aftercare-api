package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record MessageDTO(Long id, Long patientId, MessageSender sender, String senderName, String body, Instant sentAt, Instant readByStaffAt, Instant readByPatientAt) {
}
