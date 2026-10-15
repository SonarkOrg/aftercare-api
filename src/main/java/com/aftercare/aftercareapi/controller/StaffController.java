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

/** Staff directory (managed in Settings). */
@RestController
public class StaffController {

    private final StaffService staff;
    private final ActorResolver actors;

    public StaffController(StaffService staff, ActorResolver actors) {
        this.staff = staff;
        this.actors = actors;
    }

    @GetMapping("/api/staff")
    public List<StaffDTO> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return staff.list(includeInactive);
    }

    @PostMapping("/api/staff")
    public ResponseEntity<StaffDTO> create(@Valid @RequestBody StaffRequest body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staff.create(body, actors.from(jwt)));
    }

    @PutMapping("/api/staff/{id:\\d+}")
    public StaffDTO update(@PathVariable Long id, @Valid @RequestBody StaffRequest body, @AuthenticationPrincipal Jwt jwt) {
        return staff.update(id, body, actors.from(jwt));
    }

    @PostMapping("/api/staff/{id:\\d+}/deactivate")
    public StaffDTO deactivate(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return staff.deactivate(id, actors.from(jwt));
    }

    @PostMapping("/api/staff/{id:\\d+}/reactivate")
    public StaffDTO reactivate(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return staff.reactivate(id, actors.from(jwt));
    }
}
