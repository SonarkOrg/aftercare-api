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
@Table(name = "appointments", indexes = {
        @Index(name = "idx_appointments_patient", columnList = "patient_id"),
        @Index(name = "idx_appointments_scheduled_at", columnList = "scheduledAt")})
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AppointmentType type;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Specialty specialty;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "questionnaire_id", nullable = false)
    private QuestionnaireDefinition questionnaire;
    @Column(nullable = false)
    private Instant scheduledAt;
    @Column(nullable = false)
    private int durationMinutes;
    @Column(length = 255)
    private String location;
    @Column(length = 255)
    private String clinician;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AppointmentStatus status;
    private Instant reviewedAt;
    @Column(length = 255)
    private String reviewedBy;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 255)
    private String createdBy;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;
}
