package com.aftercare.aftercareapi.Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Lease row of a scheduled job. Mapped for schema validation; used only through the statements of JobLockService. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "job_locks")
public class JobLock {

    @Id
    @Column(length = 64)
    private String name;
    @Column(nullable = false)
    private Instant lockedUntil;
    @Column(length = 100)
    private String lockedBy;
    private Instant lastStartedAt;
    private Instant lastFinishedAt;
}
