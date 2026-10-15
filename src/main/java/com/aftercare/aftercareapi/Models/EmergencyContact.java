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
@Table(name = "emergency_contacts", indexes = @Index(name = "idx_contacts_patient", columnList = "patient_id"))
public class EmergencyContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 60)
    private String relationship;
    @Column(nullable = false, length = 25)
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language preferredLanguage;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 255)
    private String createdBySub;
    @Column(length = 255)
    private String createdByName;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false, length = 255)
    private String updatedBySub;
    @Column(length = 255)
    private String updatedByName;
}
