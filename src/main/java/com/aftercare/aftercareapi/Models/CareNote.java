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
@Table(name = "care_notes", indexes = @Index(name = "idx_notes_patient", columnList = "patient_id, notedAt"))
public class CareNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @Column(nullable = false, length = 5000)
    private String body;
    @Column(nullable = false)
    private Instant notedAt;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 255)
    private String authorSub;
    @Column(length = 255)
    private String authorName;
    private Instant editedAt;
    private Instant deletedAt;
    @Column(length = 255)
    private String deletedBySub;
    @Column(length = 255)
    private String deletedByName;
    @Column(length = 500)
    private String deleteReason;
    @Version
    private Long version;
}
