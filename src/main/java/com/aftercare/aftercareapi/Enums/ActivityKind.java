package com.aftercare.aftercareapi.Enums;

public enum ActivityKind {
    NOTE_ADDED(TimelineCategory.NOTE),
    CHECK_IN_SUBMITTED(TimelineCategory.CHECK_IN),
    ALERT_CREATED(TimelineCategory.ALERT),
    ALERT_ACKNOWLEDGED(TimelineCategory.ALERT),
    ALERT_RESOLVED(TimelineCategory.ALERT),
    APPOINTMENT_CREATED(TimelineCategory.APPOINTMENT),
    APPOINTMENT_RESCHEDULED(TimelineCategory.APPOINTMENT),
    APPOINTMENT_STATUS_CHANGED(TimelineCategory.APPOINTMENT),
    APPOINTMENT_INTAKE_SUBMITTED(TimelineCategory.APPOINTMENT),
    APPOINTMENT_DELETED(TimelineCategory.APPOINTMENT),
    FOLLOW_UP_STARTED(TimelineCategory.FOLLOW_UP),
    FOLLOW_UP_CLOSED(TimelineCategory.FOLLOW_UP),
    DOCUMENT_UPLOADED(TimelineCategory.DOCUMENT),
    DOCUMENT_DELETED(TimelineCategory.DOCUMENT),
    MESSAGE_FROM_PATIENT(TimelineCategory.MESSAGE),
    MESSAGE_TO_PATIENT(TimelineCategory.MESSAGE);

    private final TimelineCategory category;

    ActivityKind(TimelineCategory category) {
        this.category = category;
    }

    public TimelineCategory category() {
        return category;
    }
}
