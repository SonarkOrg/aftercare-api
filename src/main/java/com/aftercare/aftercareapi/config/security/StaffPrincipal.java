package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.StaffRole;

/** The active staff member behind the current request. Request attribute {@value #ATTRIBUTE}. */
public record StaffPrincipal(long staffId, String sub, String displayName, String email, StaffRole role,
                             AccessRole accessRole, Language uiLanguage) {

    public static final String ATTRIBUTE = "aftercare.staff";

    /** Hides the login subject, the name and the email. */
    @Override
    public String toString() {
        return "StaffPrincipal[staffId=" + staffId + ", accessRole=" + accessRole + "]";
    }
}
