package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates a submitted QuestionnaireResponse against a pinned questionnaire (spec 5.5). */
@Component
public class ResponseValidator {

    private static final Pattern LINK_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    public ValidatedResponse validate(ParsedQuestionnaire q, JsonNode body) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (body == null || !body.isObject()) {
            throw ApiException.validation("resourceType", "must be QuestionnaireResponse");
        }
        if (!"QuestionnaireResponse".equals(text(body, "resourceType"))) {
            errors.put("resourceType", "must be QuestionnaireResponse");
        }
        if (!q.canonical().equals(text(body, "questionnaire"))) {
            errors.put("questionnaire", "does not match the assigned questionnaire");
        }
        if (!"completed".equals(text(body, "status"))) {
            errors.put("status", "must be completed");
        }

        Map<String, JsonNode> submitted = new HashMap<>();
        Set<String> invalid = new HashSet<>();
        JsonNode items = body.get("item");
        if (items == null || !items.isArray()) {
            errors.put("item", "is required");
        } else {
            Set<String> seen = new HashSet<>();
            for (JsonNode item : items) {
                String linkId = text(item, "linkId");
                if (linkId == null || !LINK_ID.matcher(linkId).matches()) {
                    errors.putIfAbsent("item", "invalid structure");
                    continue;
                }
                String key = "item." + linkId;
                if (!item.isObject() || item.has("item") || !seen.add(linkId)) {
                    errors.putIfAbsent(key, "invalid structure");
                    invalid.add(linkId);
                    continue;
                }
                ParsedItem def = q.item(linkId);
                if (def == null || def.type() == ItemType.DISPLAY) {
                    errors.putIfAbsent(key, "unknown question");
                    continue;
                }
                submitted.put(linkId, item);
            }
        }

        Map<String, AnswerValue> answers = new LinkedHashMap<>();
        for (ParsedItem def : q.items()) {
            if (def.type() == ItemType.DISPLAY || invalid.contains(def.linkId())) {
                continue;
            }
            String key = "item." + def.linkId();
            JsonNode node = submitted.get(def.linkId());
            if (!isEnabled(def, answers)) {
                if (node != null) {
                    errors.putIfAbsent(key, "question is not enabled");
                }
                continue;
            }
            if (node == null) {
                if (def.required()) {
                    errors.putIfAbsent(key, "is required");
                }
                continue;
            }
            JsonNode arr = node.get("answer");
            if (arr == null || !arr.isArray() || arr.size() != 1) {
                errors.putIfAbsent(key, "must have exactly one answer");
                continue;
            }
            String problem = check(def, arr.get(0), answers);
            if (problem != null) {
                errors.putIfAbsent(key, problem);
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", errors);
        }
        return new ValidatedResponse(answers);
    }

    private static boolean isEnabled(ParsedItem def, Map<String, AnswerValue> answers) {
        EnableWhen ew = def.enableWhen();
        if (ew == null) {
            return true;
        }
        AnswerValue src = answers.get(ew.question());
        if (src instanceof AnswerValue.BoolAnswer b) {
            return ew.answerBoolean() != null && b.value() == ew.answerBoolean();
        }
        if (src instanceof AnswerValue.CodingAnswer c) {
            return ew.answerCode() != null && ew.answerCode().equals(c.code());
        }
        return false;
    }

    /** Returns an error message or null; on success stores the answer. */
    private static String check(ParsedItem def, JsonNode a, Map<String, AnswerValue> answers) {
        if (a == null || !a.isObject()) {
            return "must have exactly one answer";
        }
        switch (def.type()) {
            case BOOLEAN -> {
                JsonNode v = a.get("valueBoolean");
                if (v == null || !v.isBoolean()) {
                    return "must be true or false";
                }
                answers.put(def.linkId(), new AnswerValue.BoolAnswer(v.asBoolean()));
            }
            case INTEGER -> {
                JsonNode v = a.get("valueInteger");
                if (v == null || !v.isIntegralNumber()) {
                    return "must be an integer";
                }
                String r = range(def, v.decimalValue());
                if (r != null) {
                    return r;
                }
                answers.put(def.linkId(), new AnswerValue.NumberAnswer(v.decimalValue()));
            }
            case DECIMAL -> {
                JsonNode v = a.get("valueDecimal");
                if (v == null || !v.isNumber()) {
                    return "must be a number";
                }
                BigDecimal d = v.decimalValue();
                String r = range(def, d);
                if (r != null) {
                    return r;
                }
                int n = def.maxDecimalPlaces();
                if (d.stripTrailingZeros().scale() > n) {
                    return "must have at most " + n + " decimal" + (n == 1 ? "" : "s");
                }
                answers.put(def.linkId(), new AnswerValue.NumberAnswer(d));
            }
            case STRING, TEXT -> {
                JsonNode v = a.get("valueString");
                if (v == null || !v.isTextual() || v.asText().isBlank()) {
                    return "must be text";
                }
                String t = v.asText().trim();
                if (t.length() > def.maxLength()) {
                    return "must be at most " + def.maxLength() + " characters";
                }
                answers.put(def.linkId(), new AnswerValue.TextAnswer(t));
            }
            case CHOICE -> {
                JsonNode vc = a.get("valueCoding");
                JsonNode code = vc == null ? null : vc.get("code");
                AnswerOption opt = code != null && code.isTextual() ? def.option(code.asText()) : null;
                if (opt == null) {
                    return "must be one of the options";
                }
                answers.put(def.linkId(), new AnswerValue.CodingAnswer(opt.system(), opt.code()));
            }
            default -> {
                return "unknown question";
            }
        }
        return null;
    }

    private static String range(ParsedItem def, BigDecimal v) {
        if (v.compareTo(def.min()) < 0 || v.compareTo(def.max()) > 0) {
            return "must be between " + plain(def.min()) + " and " + plain(def.max());
        }
        return null;
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    private static String text(JsonNode n, String field) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
