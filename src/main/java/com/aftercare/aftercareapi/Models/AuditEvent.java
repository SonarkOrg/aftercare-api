package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Append-only access/change log. No foreign key to patients: rows outlive the patient. Details never hold PHI values. */
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@org.hibernate.annotations.Immutable
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_patient_time", columnList = "patientId, occurredAt"),
        @Index(name = "idx_audit_time", columnList = "occurredAt")})
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Instant occurredAt;
    @Column(nullable = false, length = 255)
    private String actorSub;
    @Column(length = 255)
    private String actorName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private AuditAction action;
    private Long patientId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditResource resourceType;
    private Long resourceId;
    @Column(length = 500)
    private String detail;

    public AuditEvent(Instant occurredAt, String actorSub, String actorName, AuditAction action, Long patientId,
                      AuditResource resourceType, Long resourceId, String detail) {
        this.occurredAt = occurredAt;
        this.actorSub = actorSub;
        this.actorName = actorName;
        this.action = action;
        this.patientId = patientId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.detail = detail;
    }
}
