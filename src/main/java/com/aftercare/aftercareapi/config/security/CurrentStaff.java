package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.Enums.Permission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Permission checks for services that redact fields. Reads the authorities set by {@link StaffContextFilter}. */
public final class CurrentStaff {

    public static final String STAFF_AUTHORITY = "STAFF";
    public static final String PERMISSION_PREFIX = "PERM_";

    private CurrentStaff() {
    }

    public static boolean has(Permission permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        String wanted = PERMISSION_PREFIX + permission.name();
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (wanted.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
