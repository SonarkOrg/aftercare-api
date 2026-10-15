package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "care_team_assignments",
        indexes = @Index(name = "idx_care_team_patient", columnList = "patient_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_care_team_patient_staff", columnNames = {"patient_id", "staff_id"}))
public class CareTeamAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private StaffMember staff;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StaffRole role;
    @Column(nullable = false)
    private Instant assignedAt;
    @Column(nullable = false, length = 255)
    private String assignedBySub;
    @Column(length = 255)
    private String assignedByName;
}
