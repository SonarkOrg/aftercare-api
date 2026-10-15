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
@Table(name = "patient_procedures", indexes = @Index(name = "idx_procedures_patient", columnList = "patient_id"))
public class PatientProcedure extends ClinicalItem {

    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false)
    private LocalDate performedOn;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "surgeon_id")
    private StaffMember surgeon;
    @Column(length = 120)
    private String surgeonName;
}
