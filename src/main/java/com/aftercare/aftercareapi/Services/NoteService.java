package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.ActivityKind;
import com.aftercare.aftercareapi.Enums.ActorType;
import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.CareNote;
import com.aftercare.aftercareapi.Models.Dto.CareNoteDTO;
import com.aftercare.aftercareapi.Models.Dto.NoteCreateRequest;
import com.aftercare.aftercareapi.Models.Dto.NoteDeleteRequest;
import com.aftercare.aftercareapi.Models.Dto.NoteEditRequest;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Repositories.CareNoteRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Care notes: the author can edit for 15 minutes; after that the note is immutable. "Entered in error" is a soft delete. */
@Service
public class NoteService {

    public static final Duration EDIT_WINDOW = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final CareNoteRepository repository;
    private final PatientRepository patientRepository;
    private final ActivityService activity;
    private final AuditService audit;
    private final Clock clock;

    public NoteService(CareNoteRepository repository, PatientRepository patientRepository, ActivityService activity,
                       AuditService audit, Clock clock) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.activity = activity;
        this.audit = audit;
        this.clock = clock;
    }

    public static CareNoteDTO toDto(CareNote n, Long patientId, String callerSub, Instant now) {
        boolean deleted = n.getDeletedAt() != null;
        Instant until = n.getCreatedAt().plus(EDIT_WINDOW);
        boolean mine = callerSub != null && callerSub.equals(n.getAuthorSub());
        return new CareNoteDTO(n.getId(), patientId, deleted ? null : n.getBody(), n.getNotedAt(), n.getCreatedAt(),
                n.getEditedAt(), n.getAuthorName(), until, !deleted && mine && now.isBefore(until), !deleted && mine,
                deleted, n.getDeletedAt(), n.getDeletedByName(), n.getDeleteReason());
    }

    @Transactional
    public CareNoteDTO create(Long patientId, NoteCreateRequest req, Actor actor) {
        Patient patient = patientRepository.findById(patientId).orElseThrow(PatientNotFoundException::new);
        Fields f = new Fields();
        String body = f.body("body", req.body(), 1, 5000);
        f.check();
        Instant now = Instant.now(clock);
        Instant notedAt = req.notedAt() == null ? now : req.notedAt();
        if (notedAt.isBefore(now.minus(Duration.ofDays(7))) || notedAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw ApiException.validation("notedAt", "must be within the last 7 days");
        }
        CareNote n = new CareNote();
        n.setPatient(patient);
        n.setBody(body);
        n.setNotedAt(notedAt);
        n.setCreatedAt(now);
        n.setAuthorSub(actor.sub());
        n.setAuthorName(actor.name());
        n = repository.saveAndFlush(n);
        activity.record(patientId, ActivityKind.NOTE_ADDED, notedAt, ActorType.STAFF, actor.sub(), actor.name(), n.getId(), null, "Note",
                ActivityTexts.noteAdded());
        audit.recordChange(actor, AuditAction.NOTE_CREATED, patientId, AuditResource.NOTE, n.getId(), null);
        log.info("Created note id={} patient={}", n.getId(), patientId);
        return toDto(n, patientId, actor.sub(), now);
    }

    @Transactional
    public CareNoteDTO edit(Long noteId, NoteEditRequest req, Actor actor) {
        CareNote n = find(noteId);
        Fields f = new Fields();
        String body = f.body("body", req.body(), 1, 5000);
        f.check();
        Instant now = Instant.now(clock);
        if (n.getDeletedAt() != null) {
            throw ApiException.invalidState("Note was entered in error");
        }
        if (!actor.sub().equals(n.getAuthorSub())) {
            throw ApiException.forbidden("NOT_AUTHOR", "Only the author can change this note");
        }
        if (!now.isBefore(n.getCreatedAt().plus(EDIT_WINDOW))) {
            throw ApiException.conflict("EDIT_WINDOW_CLOSED", "Notes can only be edited within 15 minutes");
        }
        n.setBody(body);
        n.setEditedAt(now);
        repository.saveAndFlush(n);
        Long patientId = n.getPatient().getId();
        audit.recordChange(actor, AuditAction.NOTE_EDITED, patientId, AuditResource.NOTE, n.getId(), null);
        return toDto(n, patientId, actor.sub(), now);
    }

    @Transactional
    public CareNoteDTO delete(Long noteId, NoteDeleteRequest req, Actor actor) {
        CareNote n = find(noteId);
        Fields f = new Fields();
        String reason = f.body("reason", req.reason(), 3, 500);
        f.check();
        if (n.getDeletedAt() != null) {
            throw ApiException.invalidState("Note was already entered in error");
        }
        if (!actor.sub().equals(n.getAuthorSub())) {
            throw ApiException.forbidden("NOT_AUTHOR", "Only the author can change this note");
        }
        Instant now = Instant.now(clock);
        n.setDeletedAt(now);
        n.setDeletedBySub(actor.sub());
        n.setDeletedByName(actor.name());
        n.setDeleteReason(reason);
        repository.saveAndFlush(n);
        Long patientId = n.getPatient().getId();
        audit.recordChange(actor, AuditAction.NOTE_DELETED, patientId, AuditResource.NOTE, n.getId(), "reason: " + reason);
        return toDto(n, patientId, actor.sub(), now);
    }

    private CareNote find(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Not found"));
    }
}
