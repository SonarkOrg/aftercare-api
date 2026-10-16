package com.aftercare.aftercareapi.Models.Dto;

public record RetentionCounts(long followUps, long checkIns, long alerts, long documents, long messages,
                              long notifications, long auditEvents) {

    public static final RetentionCounts ZERO = new RetentionCounts(0, 0, 0, 0, 0, 0, 0);

    /** The audit detail, e.g. {@code followUps=3,checkIns=20,...}: counts only. */
    public String detail() {
        return "followUps=" + followUps + ",checkIns=" + checkIns + ",alerts=" + alerts + ",documents=" + documents
                + ",messages=" + messages + ",notifications=" + notifications + ",auditEvents=" + auditEvents;
    }
}
