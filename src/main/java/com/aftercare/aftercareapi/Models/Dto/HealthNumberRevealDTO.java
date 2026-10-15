package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record HealthNumberRevealDTO(String healthNumber, String healthNumberMasked, Instant revealedAt) {
    @Override
    public String toString() {
        return "HealthNumberRevealDTO[***]";
    }
}
