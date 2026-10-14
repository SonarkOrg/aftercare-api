package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PatientSummaryDTO(Long id, String firstName, String lastName, String email, String phone, String gender,
                                Address address, FollowUpDTO activeFollowUp, int unresolvedAlertCount) {

    public static PatientSummaryDTO from(PatientDTO p, FollowUpDTO activeFollowUp, int unresolvedAlertCount) {
        return new PatientSummaryDTO(p.getId(), p.getFirstName(), p.getLastName(), p.getEmail(), p.getPhone(),
                p.getGender(), p.getAddress(), activeFollowUp, unresolvedAlertCount);
    }
}
