package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.Dto.ContactPreferencesDTO;
import com.aftercare.aftercareapi.Models.Dto.ContactPreferencesRequest;
import com.aftercare.aftercareapi.Models.Dto.NotificationDTO;
import com.aftercare.aftercareapi.Models.Dto.PageResponse;
import com.aftercare.aftercareapi.Services.ActorResolver;
import com.aftercare.aftercareapi.Services.notifications.ContactPreferencesService;
import com.aftercare.aftercareapi.Services.notifications.NotificationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contact preferences and consent, and the notification history of a patient. */
@RestController
public class NotificationController {

    private final ContactPreferencesService preferences;
    private final NotificationService notifications;
    private final ActorResolver actors;

    public NotificationController(ContactPreferencesService preferences, NotificationService notifications, ActorResolver actors) {
        this.preferences = preferences;
        this.notifications = notifications;
        this.actors = actors;
    }

    @GetMapping("/api/patients/{id:\\d+}/contact-preferences")
    public ContactPreferencesDTO get(@PathVariable Long id) {
        return preferences.get(id);
    }

    @PutMapping("/api/patients/{id:\\d+}/contact-preferences")
    public ContactPreferencesDTO update(@PathVariable Long id, @Valid @RequestBody ContactPreferencesRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return preferences.update(id, body, actors.from(jwt));
    }

    @GetMapping("/api/patients/{id:\\d+}/notifications")
    public PageResponse<NotificationDTO> list(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        Paging.check(page, size);
        return notifications.listForPatient(id, page, size);
    }
}
