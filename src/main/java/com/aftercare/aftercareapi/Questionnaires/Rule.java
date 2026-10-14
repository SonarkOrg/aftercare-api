package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.AlertSeverity;

import java.math.BigDecimal;
import java.util.List;

/** One parsed rule. {@code value} is set for numeric operators, {@code codes} for IN. */
public record Rule(String linkId, RuleOperator operator, BigDecimal value, List<String> codes,
                   RuleOutcome outcome, AlertSeverity severity, String messageFr, String messageEn) {
}
