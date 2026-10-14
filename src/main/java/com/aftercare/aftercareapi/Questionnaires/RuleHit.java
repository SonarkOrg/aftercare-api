package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.AlertSeverity;

public record RuleHit(String linkId, RuleOutcome outcomeType, AlertSeverity severity,
                      String messageEn, String messageFr, String answerSnapshot) {
}
