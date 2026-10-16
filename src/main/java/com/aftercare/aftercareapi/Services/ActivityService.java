package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.CareNote;
import com.aftercare.aftercareapi.Models.Dto.PageResponse;
import com.aftercare.aftercareapi.Models.Dto.TimelineItemDTO;
import com.aftercare.aftercareapi.Models.PatientActivity;
import com.aftercare.aftercareapi.Repositories.CareNoteRepository;
import com.aftercare.aftercareapi.Repositories.PatientActivityRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Clinical timeline: one row per event, appended by the services in the same transaction as the event. */
@Service
public class ActivityService {

    static final int MAX_SUMMARY = 300;

    private final PatientActivityRepository repository;
    private final CareNoteRepository noteRepository;
    private final PatientRepository patientRepository;
    private final Clock clock;

    public ActivityService(PatientActivityRepository repository, CareNoteRepository noteRepository,
                           PatientRepository patientRepository, Clock clock) {
        this.repository = repository;
        this.noteRepository = noteRepository;
        this.patientRepository = patientRepository;
        this.clock = clock;
    }

    /** Without a French twin (reads back as {@code summaryFr: null}); the services use the overload below. */
    @Transactional
    public void record(Long patientId, ActivityKind kind, Instant occurredAt, ActorType actorType, String actorSub,
                       String actorName, Long refId, AlertSeverity severity, String summary) {
        record(patientId, kind, occurredAt, actorType, actorSub, actorName, refId, severity, summary, null);
    }

    @Transactional
    public void record(Long patientId, ActivityKind kind, Instant occurredAt, ActorType actorType, String actorSub,
                       String actorName, Long refId, AlertSeverity severity, String summary, String summaryFr) {
        PatientActivity a = new PatientActivity();
        a.setPatientId(patientId);
        a.setCategory(kind.category());
        a.setKind(kind);
        a.setOccurredAt(occurredAt);
        a.setActorType(actorType);
        a.setActorSub(actorSub);
        a.setActorName(actorName);
        a.setRefId(refId);
        a.setSeverity(severity);
        a.setSummary(summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY) : summary);
        a.setSummaryFr(summaryFr == null ? null : cut(summaryFr));
        repository.save(a);
    }

    /** Cuts to the column length with an ellipsis (surrogate-pair safe). */
    public static String cut(String s) {
        if (s.length() <= MAX_SUMMARY) {
            return s;
        }
        int end = MAX_SUMMARY - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "…";
    }

    @Transactional(readOnly = true)
    public PageResponse<TimelineItemDTO> timeline(Long patientId, Set<TimelineCategory> categories, int page, int size, String callerSub) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        PageRequest pr = PageRequest.of(page, size);
        Slice<PatientActivity> slice = categories == null || categories.isEmpty()
                ? repository.findByPatientIdOrderByOccurredAtDescIdDesc(patientId, pr)
                : repository.findByPatientIdAndCategoryInOrderByOccurredAtDescIdDesc(patientId, categories, pr);
        List<PatientActivity> rows = slice.getContent();
        List<Long> noteIds = rows.stream().filter(r -> r.getKind() == ActivityKind.NOTE_ADDED && r.getRefId() != null)
                .map(PatientActivity::getRefId).distinct().toList();
        Map<Long, CareNote> notes = noteIds.isEmpty() ? Map.of()
                : noteRepository.findAllById(noteIds).stream().collect(Collectors.toMap(CareNote::getId, n -> n));
        Instant now = Instant.now(clock);
        List<TimelineItemDTO> items = rows.stream().map(r -> {
            CareNote n = r.getKind() == ActivityKind.NOTE_ADDED ? notes.get(r.getRefId()) : null;
            return new TimelineItemDTO(r.getId(), r.getCategory(), r.getKind(), r.getOccurredAt(), r.getActorType(),
                    r.getActorName(), r.getRefId(), r.getSeverity(), r.getSummary(),
                    n == null ? null : NoteService.toDto(n, patientId, callerSub, now), r.getSummaryFr());
        }).toList();
        return new PageResponse<>(items, page, size, slice.hasNext());
    }
}
