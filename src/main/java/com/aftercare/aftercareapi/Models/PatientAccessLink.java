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
@Table(name = "patient_access_links",
        indexes = {
                @Index(name = "idx_access_links_follow_up", columnList = "follow_up_id"),
                @Index(name = "idx_access_links_appointment", columnList = "appointment_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_access_links_code_hash", columnNames = "codeHash"))
public class PatientAccessLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccessLinkKind kind;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "follow_up_id")
    private FollowUp followUp;
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;
    @Column(nullable = false, length = 64)
    private String codeHash;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 255)
    private String createdBy;
    private Instant revokedAt;
}
