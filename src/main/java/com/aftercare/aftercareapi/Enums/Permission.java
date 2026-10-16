package com.aftercare.aftercareapi.Enums;

/** Declaration order is the order permissions are listed in /api/me. */
public enum Permission {
    PATIENT_READ, PATIENT_WRITE, PATIENT_DELETE,
    CLINICAL_READ, CLINICAL_WRITE, HEALTH_NUMBER_REVEAL,
    APPOINTMENT_READ, APPOINTMENT_WRITE,
    FORMS_READ, REPORTS_READ, AUDIT_READ,
    STAFF_READ, STAFF_MANAGE, SETTINGS_READ, SETTINGS_MANAGE
}
