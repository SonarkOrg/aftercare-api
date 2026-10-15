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
@Table(name = "patient_medications", indexes = @Index(name = "idx_medications_patient", columnList = "patient_id"))
public class Medication extends ClinicalItem {

    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 100)
    private String dose;
    @Column(length = 100)
    private String frequency;
    private LocalDate startDate;
}
