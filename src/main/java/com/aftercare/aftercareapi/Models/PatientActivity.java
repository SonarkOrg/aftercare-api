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
@Table(name = "patient_activity", indexes = {
        @Index(name = "idx_activity_patient_time", columnList = "patientId, occurredAt, id"),
        @Index(name = "idx_activity_patient_cat", columnList = "patientId, category")})
public class PatientActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long patientId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TimelineCategory category;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private ActivityKind kind;
    @Column(nullable = false)
    private Instant occurredAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ActorType actorType;
    @Column(length = 255)
    private String actorSub;
    @Column(length = 255)
    private String actorName;
    private Long refId;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AlertSeverity severity;
    @Column(nullable = false, length = 300)
    private String summary;
}
