package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;


/** Clinic profile and notification preferences. */
@RestController
public class SettingsController {

    private final ClinicSettingsService settings;
    private final ActorResolver actors;

    public SettingsController(ClinicSettingsService settings, ActorResolver actors) {
        this.settings = settings;
        this.actors = actors;
    }

    @GetMapping("/api/settings/clinic")
    public ClinicSettingsDTO get() {
        return settings.get();
    }

    @PutMapping("/api/settings/clinic")
    public ClinicSettingsDTO update(@Valid @RequestBody ClinicSettingsRequest body, @AuthenticationPrincipal Jwt jwt) {
        return settings.update(body, actors.from(jwt));
    }

    @GetMapping("/api/settings/notifications")
    public NotificationSettingsDTO notifications() {
        return settings.getNotifications();
    }

    @PutMapping("/api/settings/notifications")
    public NotificationSettingsDTO updateNotifications(@Valid @RequestBody NotificationSettingsRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return settings.updateNotifications(body, actors.from(jwt));
    }

    @GetMapping("/api/settings/retention")
    public RetentionSettingsDTO retention() {
        return settings.getRetention();
    }

    @PutMapping("/api/settings/retention")
    public RetentionSettingsDTO updateRetention(@Valid @RequestBody RetentionSettingsRequest body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return settings.updateRetention(body, actors.from(jwt));
    }
}
