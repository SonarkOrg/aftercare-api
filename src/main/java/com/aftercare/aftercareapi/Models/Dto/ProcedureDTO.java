package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;
import java.time.LocalDate;

public record ProcedureDTO(Long id, String name, LocalDate performedOn, Long surgeonStaffId, String surgeonName, Instant createdAt, String createdByName, Instant updatedAt, String updatedByName) {
}
