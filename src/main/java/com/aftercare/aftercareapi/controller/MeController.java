package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Dto.MeDTO;
import com.aftercare.aftercareapi.config.security.RolePermissions;
import com.aftercare.aftercareapi.config.security.StaffAccessDeniedHandler;
import com.aftercare.aftercareapi.config.security.StaffPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own identity and permissions. Not audited. */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public MeDTO me(@RequestAttribute(name = StaffPrincipal.ATTRIBUTE, required = false) StaffPrincipal staff) {
        if (staff == null) {
            throw ApiException.forbidden("NOT_STAFF", StaffAccessDeniedHandler.NOT_STAFF_MESSAGE);
        }
        return new MeDTO(staff.staffId(), staff.sub(), staff.displayName(), staff.email(), staff.role(), staff.accessRole(),
                RolePermissions.ordered(staff.accessRole()));
    }
}
