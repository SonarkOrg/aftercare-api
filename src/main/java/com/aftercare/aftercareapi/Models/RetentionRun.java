package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One retention run (dry or real): counts only, never any content. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "retention_runs", indexes = @Index(name = "idx_retention_runs_started", columnList = "startedAt"))
public class RetentionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RetentionMode mode;
    @Enumerated(EnumType.STRING)
    @Column(name = "run_trigger", nullable = false, length = 16)
    private RetentionTrigger trigger;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RetentionRunStatus status;
    @Column(nullable = false)
    private Instant startedAt;
    private Instant finishedAt;
    @Column(nullable = false)
    private long followUps;
    @Column(nullable = false)
    private long checkIns;
    @Column(nullable = false)
    private long alerts;
    @Column(nullable = false)
    private long documents;
    @Column(nullable = false)
    private long messages;
    @Column(nullable = false)
    private long notifications;
    @Column(nullable = false)
    private long auditEvents;
    @Column(nullable = false, length = 255)
    private String actorSub;
    @Column(length = 255)
    private String actorName;
}
