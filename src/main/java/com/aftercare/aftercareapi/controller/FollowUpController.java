package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.Dto.CheckInDTO;
import com.aftercare.aftercareapi.Models.Dto.CreateFollowUpRequest;
import com.aftercare.aftercareapi.Models.Dto.FollowUpDTO;
import com.aftercare.aftercareapi.Models.Dto.FollowUpWithCodeDTO;
import com.aftercare.aftercareapi.Models.Dto.NotificationDTO;
import com.aftercare.aftercareapi.Models.Dto.SendLinkRequest;
import com.aftercare.aftercareapi.Services.ActorResolver;
import com.aftercare.aftercareapi.Services.CheckInService;
import com.aftercare.aftercareapi.Services.notifications.NotificationService;
import com.aftercare.aftercareapi.Services.FollowUpService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class FollowUpController {

    private final FollowUpService followUpService;
    private final CheckInService checkInService;
    private final NotificationService notifications;
    private final ActorResolver actors;

    public FollowUpController(FollowUpService followUpService, CheckInService checkInService,
                              NotificationService notifications, ActorResolver actors) {
        this.followUpService = followUpService;
        this.checkInService = checkInService;
        this.notifications = notifications;
        this.actors = actors;
    }

    @PostMapping("/api/patients/{patientId:\\d+}/follow-ups")
    public ResponseEntity<FollowUpWithCodeDTO> create(@PathVariable Long patientId,
                                                      @Valid @RequestBody CreateFollowUpRequest request,
                                                      @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(followUpService.create(patientId, request, jwt.getSubject()));
    }

    @GetMapping("/api/patients/{patientId:\\d+}/follow-ups")
    public List<FollowUpDTO> list(@PathVariable Long patientId) {
        return followUpService.listForPatient(patientId);
    }

    @PostMapping("/api/follow-ups/{id:\\d+}/access-code")
    public FollowUpWithCodeDTO regenerate(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return followUpService.regenerateCode(id, jwt.getSubject());
    }

    @PostMapping("/api/follow-ups/{id:\\d+}/send-link")
    public ResponseEntity<NotificationDTO> sendLink(@PathVariable Long id,
                                                    @RequestBody(required = false) SendLinkRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.accepted().body(notifications.sendFollowUpLink(id, body == null ? null : body.channel(), actors.from(jwt)));
    }

    @PostMapping("/api/follow-ups/{id:\\d+}/close")
    public FollowUpDTO close(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return followUpService.close(id, jwt.getSubject());
    }

    @GetMapping("/api/patients/{patientId:\\d+}/check-ins")
    public List<CheckInDTO> checkIns(@PathVariable Long patientId) {
        return checkInService.listForPatient(patientId);
    }

    @GetMapping("/api/check-ins")
    public List<CheckInDTO> checkInRange(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return checkInService.listRange(from, to);
    }
}
