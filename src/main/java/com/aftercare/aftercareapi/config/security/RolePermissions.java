package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.Enums.Permission;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aftercare.aftercareapi.Enums.Permission.*;

/** The only copy of the role to permission map. The front end receives permissions, never roles' meanings. */
public final class RolePermissions {

    private static final Map<AccessRole, EnumSet<Permission>> MAP = new EnumMap<>(AccessRole.class);

    static {
        MAP.put(AccessRole.ADMIN, EnumSet.allOf(Permission.class));
        EnumSet<Permission> clinician = EnumSet.of(PATIENT_READ, PATIENT_WRITE, CLINICAL_READ, CLINICAL_WRITE,
                HEALTH_NUMBER_REVEAL, APPOINTMENT_READ, APPOINTMENT_WRITE, FORMS_READ, REPORTS_READ, STAFF_READ,
                SETTINGS_READ);
        MAP.put(AccessRole.PHYSICIAN, clinician);
        MAP.put(AccessRole.NURSE, EnumSet.copyOf(clinician));
        MAP.put(AccessRole.RECEPTION, EnumSet.of(PATIENT_READ, PATIENT_WRITE, APPOINTMENT_READ, APPOINTMENT_WRITE,
                FORMS_READ, STAFF_READ, SETTINGS_READ));
    }

    private RolePermissions() {
    }

    public static Set<Permission> of(AccessRole role) {
        return java.util.Collections.unmodifiableSet(MAP.get(role));
    }

    /** In declaration order (the order of the contract table). */
    public static List<Permission> ordered(AccessRole role) {
        return MAP.get(role).stream().toList();
    }
}
