package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.LocalDate;

public record MedicationRequest(String name, String dose, String frequency, LocalDate startDate) {
}
