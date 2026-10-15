package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/** Care notes and the patient timeline. */
@RestController
public class NoteController {

    private final ActivityService activity;
    private final NoteService notes;
    private final ActorResolver actors;

    public NoteController(ActivityService activity, NoteService notes, ActorResolver actors) {
        this.activity = activity;
        this.notes = notes;
        this.actors = actors;
    }

    @GetMapping("/api/patients/{id:\\d+}/timeline")
    public PageResponse<TimelineItemDTO> timeline(@PathVariable Long id,
                                                  @RequestParam(name = "category", required = false) List<TimelineCategory> category,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "30") int size,
                                                  @AuthenticationPrincipal Jwt jwt) {
        Paging.check(page, size);
        return activity.timeline(id, category == null ? Set.of() : Set.copyOf(category), page, size, jwt.getSubject());
    }

    @PostMapping("/api/patients/{id:\\d+}/notes")
    public ResponseEntity<CareNoteDTO> create(@PathVariable Long id, @Valid @RequestBody NoteCreateRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notes.create(id, body, actors.from(jwt)));
    }

    @PutMapping("/api/notes/{noteId:\\d+}")
    public CareNoteDTO edit(@PathVariable Long noteId, @Valid @RequestBody NoteEditRequest body, @AuthenticationPrincipal Jwt jwt) {
        return notes.edit(noteId, body, actors.from(jwt));
    }

    @PostMapping("/api/notes/{noteId:\\d+}/delete")
    public CareNoteDTO delete(@PathVariable Long noteId, @Valid @RequestBody NoteDeleteRequest body, @AuthenticationPrincipal Jwt jwt) {
        return notes.delete(noteId, body, actors.from(jwt));
    }
}
