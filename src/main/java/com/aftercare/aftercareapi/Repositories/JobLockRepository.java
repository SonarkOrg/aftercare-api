package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Models.JobLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface JobLockRepository extends JpaRepository<JobLock, String> {

    /** Takes the lease when it is free (expired or released). Exactly one row updated means acquired. */
    @Modifying
    @Query("update JobLock j set j.lockedUntil = :until, j.lockedBy = :by, j.lastStartedAt = :now "
            + "where j.name = :name and j.lockedUntil <= :now")
    int acquire(@Param("name") String name, @Param("until") Instant until, @Param("by") String by, @Param("now") Instant now);

    @Modifying
    @Query("update JobLock j set j.lockedUntil = :now, j.lastFinishedAt = :now where j.name = :name and j.lockedBy = :by")
    int release(@Param("name") String name, @Param("by") String by, @Param("now") Instant now);
}
