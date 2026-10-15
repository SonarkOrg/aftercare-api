package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.util.List;

public record PublicThreadDTO(String clinicName, String clinicPhone, List<PublicMessageDTO> items, boolean hasMore, long unreadCount) {
}
