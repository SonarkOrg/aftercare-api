package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.LocalDate;

public record ProcedureRequest(String name, LocalDate performedOn, Long surgeonStaffId, String surgeonName) {
}
