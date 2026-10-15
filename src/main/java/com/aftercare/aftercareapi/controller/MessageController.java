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

/** Staff messaging: the shared clinic inbox and per-patient threads. */
@RestController
public class MessageController {

    private final MessageService messages;
    private final ActorResolver actors;
    private final AuditService audit;

    public MessageController(MessageService messages, ActorResolver actors, AuditService audit) {
        this.messages = messages;
        this.actors = actors;
        this.audit = audit;
    }

    @GetMapping("/api/messages/inbox")
    public List<ConversationDTO> inbox() {
        return messages.inbox();
    }

    @GetMapping("/api/messages/unread-count")
    public UnreadCountDTO unreadCount() {
        return messages.unreadCount();
    }

    @GetMapping("/api/patients/{id:\\d+}/messages")
    public ThreadDTO thread(@PathVariable Long id, @RequestParam(required = false) Long before,
                            @RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal Jwt jwt) {
        ThreadDTO result = messages.thread(id, before, limit);
        if (before == null) {
            audit.recordAccess(actors.from(jwt), AuditAction.MESSAGES_VIEWED, id, AuditResource.MESSAGE, null, null);
        }
        return result;
    }

    @PostMapping("/api/patients/{id:\\d+}/messages")
    public ResponseEntity<MessageDTO> send(@PathVariable Long id, @Valid @RequestBody MessageSendRequest body,
                                           @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messages.send(id, body, actors.from(jwt)));
    }

    @PostMapping("/api/patients/{id:\\d+}/messages/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, @Valid @RequestBody MarkReadRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        messages.markRead(id, body, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
