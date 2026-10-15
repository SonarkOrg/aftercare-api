package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "patient_allergies", indexes = @Index(name = "idx_allergies_patient", columnList = "patient_id"))
public class Allergy extends ClinicalItem {

    @Column(nullable = false, length = 200)
    private String substance;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AllergySeverity severity;
    @Column(length = 500)
    private String reaction;
}
