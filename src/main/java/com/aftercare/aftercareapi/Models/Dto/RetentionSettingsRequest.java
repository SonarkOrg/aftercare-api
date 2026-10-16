package com.aftercare.aftercareapi.Models.Dto;

/** Each value is null (keep indefinitely) or an integer 1..1200, validated by the service. */
public record RetentionSettingsRequest(Integer closedFollowUpMonths, Integer deletedDocumentMonths, Integer messageMonths,
                                       Integer notificationMonths, Integer auditMonths) {
}
