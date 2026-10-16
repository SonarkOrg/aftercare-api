package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.NotificationChannel;

/** {@code channel} null means the patient's preferred channel. */
public record SendLinkRequest(NotificationChannel channel) {
}
