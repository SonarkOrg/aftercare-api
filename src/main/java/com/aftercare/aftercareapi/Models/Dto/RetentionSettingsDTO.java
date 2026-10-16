package com.aftercare.aftercareapi.Models.Dto;

import java.time.Instant;

public record RetentionSettingsDTO(Integer closedFollowUpMonths, Integer deletedDocumentMonths, Integer messageMonths,
                                   Integer notificationMonths, Integer auditMonths, boolean purgeEnabled, boolean dryRun,
                                   Instant updatedAt, String updatedByName) {
}
