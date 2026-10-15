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
@Table(name = "patient_documents", indexes = @Index(name = "idx_documents_patient", columnList = "patient_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_documents_storage_key", columnNames = "storageKey"))
public class PatientDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @Column(nullable = false, length = 120)
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentCategory category;
    @Column(nullable = false, length = 32)
    private String contentType;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(nullable = false, length = 255)
    private String originalFilename;
    @Column(nullable = false, length = 64)
    private String storageKey;
    @Column(nullable = false)
    private Instant uploadedAt;
    @Column(nullable = false, length = 255)
    private String uploadedBySub;
    @Column(length = 255)
    private String uploadedByName;
    private Instant deletedAt;
    @Column(length = 255)
    private String deletedBySub;
    @Column(length = 255)
    private String deletedByName;
    @Column(length = 500)
    private String deleteReason;
}
