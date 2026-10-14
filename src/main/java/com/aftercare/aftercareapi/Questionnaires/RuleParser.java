package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.QuestionnairePurpose;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Parses and validates the sibling {@code rules} array (spec 6.1). */
public final class RuleParser {

    private static final Set<String> RULE_KEYS = Set.of("linkId", "operator", "value", "outcome");
    private static final Set<String> OUTCOME_KEYS = Set.of("type", "severity", "message");

    private RuleParser() {
    }

    public static List<Rule> parse(JsonNode rulesNode, ParsedQuestionnaire q, QuestionnairePurpose purpose) {
        if (rulesNode == null || !rulesNode.isArray()) {
            throw bad("rules must be an array");
        }
        List<Rule> out = new ArrayList<>();
        int idx = 0;
        for (JsonNode r : rulesNode) {
            out.add(parseRule(r, q, purpose, "rule #" + (++idx)));
        }
        return List.copyOf(out);
    }

    private static Rule parseRule(JsonNode r, ParsedQuestionnaire q, QuestionnairePurpose purpose, String where) {
        if (!r.isObject()) {
            throw bad(where + " must be an object");
        }
        checkKeys(r, RULE_KEYS, where);
        String linkId = text(r, "linkId");
        ParsedItem item = linkId == null ? null : q.item(linkId);
        if (item == null) {
            throw bad(where + ": unknown linkId");
        }
        where = where + " (" + linkId + ")";
        if (item.type() == ItemType.DISPLAY) {
            throw bad(where + ": rules cannot reference display items");
        }
        if (item.type() == ItemType.STRING || item.type() == ItemType.TEXT) {
            throw bad(where + ": rules cannot target string/text items");
        }
        RuleOperator op = RuleOperator.fromSymbol(text(r, "operator"))
                .orElseThrow(() -> bad(r.path("linkId").asText() + ": unknown operator"));

        BigDecimal value = null;
        List<String> codes = List.of();
        switch (op) {
            case GTE, LTE, EQ -> {
                if (item.type() != ItemType.INTEGER && item.type() != ItemType.DECIMAL) {
                    throw bad(where + ": operator " + op.symbol() + " requires an integer/decimal item");
                }
                JsonNode v = r.get("value");
                if (v == null || !v.isNumber()) {
                    throw bad(where + ": value must be a number");
                }
                value = v.decimalValue();
            }
            case EQUALS_TRUE, EQUALS_FALSE -> {
                if (item.type() != ItemType.BOOLEAN) {
                    throw bad(where + ": operator " + op.symbol() + " requires a boolean item");
                }
                if (r.has("value")) {
                    throw bad(where + ": value must be absent");
                }
            }
            case IN -> {
                if (item.type() != ItemType.CHOICE) {
                    throw bad(where + ": operator in requires a choice item");
                }
                JsonNode v = r.get("value");
                if (v == null || !v.isArray() || v.isEmpty()) {
                    throw bad(where + ": value must be a non-empty array of option codes");
                }
                List<String> list = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (JsonNode c : v) {
                    if (!c.isTextual() || item.option(c.asText()) == null) {
                        throw bad(where + ": value contains an unknown option code");
                    }
                    if (seen.add(c.asText())) {
                        list.add(c.asText());
                    }
                }
                codes = List.copyOf(list);
            }
        }

        JsonNode o = r.get("outcome");
        if (o == null || !o.isObject()) {
            throw bad(where + ": outcome is required");
        }
        checkKeys(o, OUTCOME_KEYS, where + " outcome");
        String type = text(o, "type");
        RuleOutcome outcome;
        AlertSeverity severity = null;
        if ("ALERT".equals(type)) {
            outcome = RuleOutcome.ALERT;
            if (purpose != QuestionnairePurpose.FOLLOW_UP_CHECKIN) {
                throw bad(where + ": ALERT is only allowed in FOLLOW_UP_CHECKIN forms");
            }
            String sev = text(o, "severity");
            try {
                severity = sev == null ? null : AlertSeverity.valueOf(sev);
            } catch (IllegalArgumentException e) {
                severity = null;
            }
            if (severity == null) {
                throw bad(where + ": ALERT requires severity HIGH or MEDIUM");
            }
        } else if ("NEEDS_REVIEW".equals(type)) {
            outcome = RuleOutcome.NEEDS_REVIEW;
            if (purpose != QuestionnairePurpose.PRE_APPOINTMENT) {
                throw bad(where + ": NEEDS_REVIEW is only allowed in PRE_APPOINTMENT forms");
            }
            if (o.has("severity")) {
                throw bad(where + ": NEEDS_REVIEW must not have a severity");
            }
        } else {
            throw bad(where + ": outcome type must be ALERT or NEEDS_REVIEW");
        }
        JsonNode m = o.get("message");
        if (m == null || !m.isObject()) {
            throw bad(where + ": message is required");
        }
        checkKeys(m, Set.of("fr", "en"), where + " message");
        String fr = text(m, "fr");
        String en = text(m, "en");
        if (fr == null || fr.isBlank() || fr.length() > 400 || en == null || en.isBlank() || en.length() > 400) {
            throw bad(where + ": message.fr and message.en are required (max 400 characters)");
        }
        return new Rule(linkId, op, value, codes, outcome, severity, fr, en);
    }

    private static void checkKeys(JsonNode n, Set<String> allowed, String where) {
        Iterator<String> it = n.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (!allowed.contains(k)) {
                throw bad(where + ": unsupported element " + k);
            }
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static InvalidQuestionnaireException bad(String m) {
        return new InvalidQuestionnaireException(m);
    }
}
