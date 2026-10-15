package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/api/reports/summary")
    public ReportSummaryDTO summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.summary(from, to);
    }
}
