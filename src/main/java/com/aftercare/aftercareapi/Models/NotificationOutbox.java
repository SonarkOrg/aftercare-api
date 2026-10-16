package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One queued or delivered patient notification. Holds references only: no message body, no link, no code. The recipient
 * is stored masked, and only after an attempt. There is deliberately no foreign key (rows are cleaned on patient delete
 * and by retention).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notification_outbox",
        indexes = {
                @Index(name = "idx_outbox_status_next", columnList = "status, nextAttemptAt"),
                @Index(name = "idx_outbox_patient_created", columnList = "patientId, createdAt"),
                @Index(name = "idx_outbox_follow_up", columnList = "followUpId"),
                @Index(name = "idx_outbox_appointment", columnList = "appointmentId")},
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_dedupe", columnNames = "dedupeKey"))
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long patientId;
    private Long followUpId;
    private Long appointmentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NotificationChannel channel;
    @Enumerated(EnumType.STRING)
    @Column(name = "template_key", nullable = false, length = 32)
    private NotificationTemplate template;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language language;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;
    @Column(nullable = false)
    private boolean automated;
    @Column(length = 120)
    private String dedupeKey;
    @Column(nullable = false)
    private int attempts;
    @Column(nullable = false)
    private Instant nextAttemptAt;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private NotificationError lastError;
    @Column(length = 80)
    private String recipientMasked;
    @Column(length = 64)
    private String providerMessageId;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 255)
    private String createdBySub;
    @Column(length = 255)
    private String createdByName;
    private Instant sentAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;
}
