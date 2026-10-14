package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "follow_ups", indexes = @Index(name = "idx_follow_ups_patient", columnList = "patient_id"))
public class FollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Specialty specialty;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "questionnaire_id", nullable = false)
    private QuestionnaireDefinition questionnaire;
    @Column(nullable = false)
    private LocalDate dischargeDate;
    @Column(nullable = false)
    private int durationDays;
    @Column(nullable = false)
    private LocalDate endDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FollowUpStatus status;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 255)
    private String createdBy;
    private Instant closedAt;
    @Column(length = 255)
    private String closedBy;
    @Version
    private Long version;
}
