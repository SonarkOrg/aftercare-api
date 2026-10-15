package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "patient_diagnoses", indexes = @Index(name = "idx_diagnoses_patient", columnList = "patient_id"))
public class Diagnosis extends ClinicalItem {

    @Column(nullable = false, length = 300)
    private String description;
    @Column(length = 16)
    private String icd10Code;
    private LocalDate diagnosedOn;
}
