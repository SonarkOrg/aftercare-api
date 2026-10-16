package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Dto.MeDTO;
import com.aftercare.aftercareapi.Models.Dto.MePreferencesRequest;
import com.aftercare.aftercareapi.Services.StaffAccessService;
import com.aftercare.aftercareapi.config.security.RolePermissions;
import com.aftercare.aftercareapi.config.security.StaffAccessDeniedHandler;
import com.aftercare.aftercareapi.config.security.StaffPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own identity, permissions and preferences. Not audited. */
@RestController
public class MeController {

    private final StaffAccessService staffAccess;

    public MeController(StaffAccessService staffAccess) {
        this.staffAccess = staffAccess;
    }

    @GetMapping("/api/me")
    public MeDTO me(@RequestAttribute(name = StaffPrincipal.ATTRIBUTE, required = false) StaffPrincipal staff) {
        return toDto(requireStaff(staff));
    }

    /** Changes only the caller's own row (there is no id parameter). */
    @PutMapping("/api/me/preferences")
    public MeDTO preferences(@RequestAttribute(name = StaffPrincipal.ATTRIBUTE, required = false) StaffPrincipal staff,
                             @RequestBody MePreferencesRequest req) {
        // Checked by hand (not @Valid) so that a login without an active staff member gets NOT_STAFF before any validation error.
        StaffPrincipal current = requireStaff(staff);
        if (req == null || req.uiLanguage() == null) {
            throw ApiException.validation("uiLanguage", "must not be null");
        }
        return toDto(staffAccess.updateUiLanguage(current.staffId(), req.uiLanguage()));
    }

    private static StaffPrincipal requireStaff(StaffPrincipal staff) {
        if (staff == null) {
            throw ApiException.forbidden("NOT_STAFF", StaffAccessDeniedHandler.NOT_STAFF_MESSAGE);
        }
        return staff;
    }

    private static MeDTO toDto(StaffPrincipal staff) {
        return new MeDTO(staff.staffId(), staff.sub(), staff.displayName(), staff.email(), staff.role(), staff.accessRole(),
                RolePermissions.ordered(staff.accessRole()), staff.uiLanguage());
    }
}
