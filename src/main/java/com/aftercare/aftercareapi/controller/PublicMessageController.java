package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


/** Patient messaging, authenticated only by the access code in the path. Never accepts or requires a JWT. */
@RestController
public class PublicMessageController {

    private final PublicMessageService service;

    public PublicMessageController(PublicMessageService service) {
        this.service = service;
    }

    @GetMapping("/api/public/q/{code}/messages")
    public ResponseEntity<PublicThreadDTO> thread(@PathVariable String code, @RequestParam(required = false) Long before,
                                                  @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.thread(code, before, limit));
    }

    @PostMapping("/api/public/q/{code}/messages")
    public ResponseEntity<PublicMessageDTO> send(@PathVariable String code, @Valid @RequestBody MessageSendRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.send(code, body));
    }

    @PostMapping("/api/public/q/{code}/messages/read")
    public ResponseEntity<Void> markRead(@PathVariable String code, @Valid @RequestBody MarkReadRequest body) {
        service.markRead(code, body);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
