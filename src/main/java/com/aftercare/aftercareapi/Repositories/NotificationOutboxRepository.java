package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.NotificationOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    @Modifying
    @Query("delete from NotificationOutbox n where n.patientId = :patientId")
    int deleteByPatientId(@Param("patientId") Long patientId);

    /** Atomic claim: only one caller can move a PENDING row to SENDING. Returns the number of rows claimed (0 or 1). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationOutbox n set n.status = com.aftercare.aftercareapi.Enums.NotificationStatus.SENDING, "
            + "n.attempts = n.attempts + 1, n.updatedAt = :now, n.version = n.version + 1 "
            + "where n.id = :id and n.status = com.aftercare.aftercareapi.Enums.NotificationStatus.PENDING")
    int claim(@Param("id") Long id, @Param("now") Instant now);

    /** Cancels the PENDING rows of a patient on one channel (contact detail changed, consent reset). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationOutbox n set n.status = com.aftercare.aftercareapi.Enums.NotificationStatus.CANCELLED, "
            + "n.lastError = com.aftercare.aftercareapi.Enums.NotificationError.NO_CONSENT, n.updatedAt = :now, "
            + "n.version = n.version + 1 where n.patientId = :patientId and n.channel = :channel "
            + "and n.status = com.aftercare.aftercareapi.Enums.NotificationStatus.PENDING")
    int cancelPending(@Param("patientId") Long patientId, @Param("channel") NotificationChannel channel,
                      @Param("now") Instant now);

    Slice<NotificationOutbox> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId, Pageable pageable);

    Slice<NotificationOutbox> findByPatientIdAndTemplateInOrderByCreatedAtDescIdDesc(Long patientId,
                                                                                    Collection<NotificationTemplate> templates,
                                                                                    Pageable pageable);

    @Query("select count(n) from NotificationOutbox n where n.patientId = :patientId and n.createdAt > :since "
            + "and n.status in :statuses")
    long countRecentForPatient(@Param("patientId") Long patientId, @Param("since") Instant since,
                               @Param("statuses") Collection<NotificationStatus> statuses);

    long countByAutomatedFalseAndTemplateAndFollowUpIdAndCreatedAtAfter(NotificationTemplate template, Long followUpId, Instant since);

    long countByAutomatedFalseAndTemplateAndAppointmentIdAndCreatedAtAfter(NotificationTemplate template, Long appointmentId, Instant since);

    long countByStatusAndSentAtAfter(NotificationStatus status, Instant after);

    List<NotificationOutbox> findByStatusAndUpdatedAtBefore(NotificationStatus status, Instant before);

    List<NotificationOutbox> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(NotificationStatus status, Instant now,
                                                                                    Pageable pageable);
}
