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
@Table(name = "alerts", indexes = {
        @Index(name = "idx_alerts_patient", columnList = "patient_id"),
        @Index(name = "idx_alerts_status", columnList = "status")})
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follow_up_id", nullable = false)
    private FollowUp followUp;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_in_id", nullable = false)
    private CheckIn checkIn;
    @Column(nullable = false, length = 64)
    private String linkId;
    @Column(nullable = false, length = 255)
    private String answerValue;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AlertSeverity severity;
    @Column(nullable = false, length = 500)
    private String messageEn;
    @Column(nullable = false, length = 500)
    private String messageFr;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AlertStatus status;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant acknowledgedAt;
    @Column(length = 255)
    private String acknowledgedBy;
    private Instant resolvedAt;
    @Column(length = 255)
    private String resolvedBy;
    @Column(length = 1000)
    private String resolutionNote;
    @Version
    private Long version;
}
