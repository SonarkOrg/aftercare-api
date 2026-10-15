package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Shared columns of the soft-deletable medical lists (allergies, medications, procedures, diagnoses). */
@Getter
@Setter
@MappedSuperclass
public abstract class ClinicalItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
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
    private Instant deletedAt;
    @Column(length = 255)
    private String deletedBySub;
    @Column(length = 255)
    private String deletedByName;
    @Version
    private Long version;
}
