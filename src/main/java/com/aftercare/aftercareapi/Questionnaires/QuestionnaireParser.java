package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.QuestionnaireStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates the supported FHIR R4 Questionnaire subset (spec sections 5.2 to 5.4).
 * Any violation throws {@link InvalidQuestionnaireException} with a plain problem description.
 */
public final class QuestionnaireParser {

    public static final String TRANSLATION_URL = "http://hl7.org/fhir/StructureDefinition/translation";
    static final String MIN_URL = "http://hl7.org/fhir/StructureDefinition/minValue";
    static final String MAX_URL = "http://hl7.org/fhir/StructureDefinition/maxValue";
    static final String DECIMALS_URL = "http://hl7.org/fhir/StructureDefinition/maxDecimalPlaces";
    static final String UNIT_URL = "http://hl7.org/fhir/StructureDefinition/questionnaire-unit";

    private static final Pattern LINK_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Pattern VERSION = Pattern.compile("^\\d+$");
    private static final Set<String> TOP_KEYS = Set.of("resourceType", "url", "version", "status", "language", "title",
            "_title", "item", "id", "name", "publisher", "date", "description", "meta");
    private static final Set<String> ITEM_KEYS = Set.of("linkId", "type", "text", "_text", "required", "repeats",
            "readOnly", "maxLength", "code", "answerOption", "extension", "enableWhen", "item");
    private static final Set<String> CHILD_KEYS = Set.of("linkId", "type", "text", "_text");
    private static final Set<String> OPTION_CODING_KEYS = Set.of("system", "code", "display", "_display");

    private QuestionnaireParser() {
    }

    /** FHIR translation lookup shared with the frontend: "fr" returns the base string, "en" the translation. */
    public static String localize(String base, JsonNode underscoreElement, String lang) {
        if ("en".equals(lang)) {
            String en = englishTranslation(underscoreElement);
            if (en != null) {
                return en;
            }
        }
        return base;
    }

    static String englishTranslation(JsonNode underscoreElement) {
        if (underscoreElement == null || !underscoreElement.path("extension").isArray()) {
            return null;
        }
        for (JsonNode ext : underscoreElement.get("extension")) {
            if (!TRANSLATION_URL.equals(ext.path("url").asText(null))) {
                continue;
            }
            String lang = null;
            String content = null;
            for (JsonNode inner : ext.path("extension")) {
                String url = inner.path("url").asText(null);
                if ("lang".equals(url)) {
                    lang = inner.path("valueCode").asText(null);
                } else if ("content".equals(url)) {
                    content = inner.path("valueString").asText(null);
                }
            }
            if ("en".equals(lang) && content != null) {
                return content;
            }
        }
        return null;
    }

    public static ParsedQuestionnaire parse(JsonNode q) {
        if (q == null || !q.isObject()) {
            throw bad("questionnaire must be an object");
        }
        checkKeys(q, TOP_KEYS, "questionnaire");
        if (!"Questionnaire".equals(textOrNull(q, "resourceType"))) {
            throw bad("resourceType must be Questionnaire");
        }
        String url = textOrNull(q, "url");
        if (url == null || url.isBlank() || url.length() > 255) {
            throw bad("url is required (max 255 characters)");
        }
        String version = textOrNull(q, "version");
        if (version == null || !VERSION.matcher(version).matches() || version.length() > 32) {
            throw bad("version must be digits only");
        }
        QuestionnaireStatus status;
        String st = textOrNull(q, "status");
        if ("active".equals(st)) {
            status = QuestionnaireStatus.ACTIVE;
        } else if ("retired".equals(st)) {
            status = QuestionnaireStatus.RETIRED;
        } else {
            throw bad("status must be active or retired");
        }
        if (!"fr-CA".equals(textOrNull(q, "language"))) {
            throw bad("language must be fr-CA");
        }
        String[] title = bilingual(q, "title", "_title", "questionnaire title");
        if (title[0].length() > 255 || title[1].length() > 255) {
            throw bad("title must be at most 255 characters");
        }
        JsonNode items = q.get("item");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw bad("item must be a non-empty array");
        }

        Set<String> seen = new HashSet<>();
        Map<String, ParsedItem> earlier = new LinkedHashMap<>();
        List<ParsedItem> parsed = new ArrayList<>();
        for (JsonNode itemNode : items) {
            ParsedItem pi = parseItem(itemNode, seen, earlier);
            parsed.add(pi);
            earlier.put(pi.linkId(), pi);
        }
        return new ParsedQuestionnaire(url, version, status, title[0], title[1], List.copyOf(parsed), List.of());
    }

    private static ParsedItem parseItem(JsonNode n, Set<String> seen, Map<String, ParsedItem> earlier) {
        if (!n.isObject()) {
            throw bad("item must be an object");
        }
        String linkId = textOrNull(n, "linkId");
        if (linkId == null || !LINK_ID.matcher(linkId).matches()) {
            throw bad("item has an invalid linkId");
        }
        if (!seen.add(linkId)) {
            throw bad("duplicate linkId " + linkId);
        }
        String where = "item " + linkId;
        checkKeys(n, ITEM_KEYS, where);
        ItemType type = ItemType.fromFhir(textOrNull(n, "type"))
                .orElseThrow(() -> bad(where + ": unsupported type"));
        String[] text = bilingual(n, "text", "_text", where);

        boolean required = false;
        if (n.has("required")) {
            if (type == ItemType.DISPLAY) {
                throw bad(where + ": required is not allowed on display items");
            }
            if (!n.get("required").isBoolean()) {
                throw bad(where + ": required must be boolean");
            }
            required = n.get("required").asBoolean();
        }
        if (n.has("repeats") && !(n.get("repeats").isBoolean() && !n.get("repeats").asBoolean())) {
            throw bad(where + ": repeats is not supported");
        }
        if (n.has("readOnly") && !(n.get("readOnly").isBoolean() && !n.get("readOnly").asBoolean())) {
            throw bad(where + ": readOnly is not supported");
        }

        Integer maxLength = null;
        boolean textual = type == ItemType.STRING || type == ItemType.TEXT;
        if (n.has("maxLength")) {
            if (!textual) {
                throw bad(where + ": maxLength is only allowed on string/text");
            }
            JsonNode ml = n.get("maxLength");
            if (!ml.isIntegralNumber() || ml.asInt() < 1 || ml.asInt() > 1000) {
                throw bad(where + ": maxLength must be an integer between 1 and 1000");
            }
            maxLength = ml.asInt();
        } else if (textual) {
            throw bad(where + ": maxLength is required for string/text");
        }

        if (n.has("code")) {
            JsonNode code = n.get("code");
            if (!code.isArray()) {
                throw bad(where + ": code must be an array");
            }
            for (JsonNode c : code) {
                if (!c.isObject()) {
                    throw bad(where + ": code entries must be objects");
                }
            }
        }

        List<AnswerOption> options = List.of();
        if (n.has("answerOption")) {
            if (type != ItemType.CHOICE) {
                throw bad(where + ": answerOption is only allowed on choice items");
            }
            options = parseOptions(n.get("answerOption"), where);
        } else if (type == ItemType.CHOICE) {
            throw bad(where + ": choice requires answerOption");
        }

        BigDecimal[] range = new BigDecimal[2];
        Integer[] decimals = new Integer[1];
        parseExtensions(n, type, where, range, decimals);

        EnableWhen enableWhen = null;
        if (n.has("enableWhen")) {
            enableWhen = parseEnableWhen(n.get("enableWhen"), where, earlier);
        }

        if (n.has("item")) {
            if (type == ItemType.DISPLAY) {
                throw bad(where + ": display items cannot have nested items");
            }
            if (!n.get("item").isArray()) {
                throw bad(where + ": item must be an array");
            }
            for (JsonNode child : n.get("item")) {
                if (!child.isObject()) {
                    throw bad(where + ": nested item must be an object");
                }
                String cid = textOrNull(child, "linkId");
                if (cid == null || !LINK_ID.matcher(cid).matches()) {
                    throw bad(where + ": nested item has an invalid linkId");
                }
                if (!seen.add(cid)) {
                    throw bad("duplicate linkId " + cid);
                }
                checkKeys(child, CHILD_KEYS, "item " + cid);
                if (!"display".equals(textOrNull(child, "type"))) {
                    throw bad("item " + cid + ": only display items may be nested");
                }
                bilingual(child, "text", "_text", "item " + cid);
            }
        }
        return new ParsedItem(linkId, type, text[0], text[1], required, maxLength, range[0], range[1], decimals[0],
                options, enableWhen);
    }

    private static List<AnswerOption> parseOptions(JsonNode arr, String where) {
        if (!arr.isArray() || arr.isEmpty()) {
            throw bad(where + ": answerOption must be a non-empty array");
        }
        List<AnswerOption> out = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode o : arr) {
            if (!o.isObject()) {
                throw bad(where + ": answerOption entries must be objects");
            }
            checkKeys(o, Set.of("valueCoding"), where + " answerOption");
            JsonNode vc = o.get("valueCoding");
            if (vc == null || !vc.isObject()) {
                throw bad(where + ": answerOption requires valueCoding");
            }
            checkKeys(vc, OPTION_CODING_KEYS, where + " valueCoding");
            String system = textOrNull(vc, "system");
            String code = textOrNull(vc, "code");
            if (system == null || system.isBlank() || code == null || code.isBlank()) {
                throw bad(where + ": valueCoding requires system and code");
            }
            if (!codes.add(code)) {
                throw bad(where + ": duplicate option code");
            }
            String[] display = bilingual(vc, "display", "_display", where + " option " + code);
            out.add(new AnswerOption(system, code, display[0], display[1]));
        }
        return List.copyOf(out);
    }

    private static void parseExtensions(JsonNode n, ItemType type, String where, BigDecimal[] range, Integer[] decimals) {
        boolean numeric = type == ItemType.INTEGER || type == ItemType.DECIMAL;
        Set<String> urls = new HashSet<>();
        if (n.has("extension")) {
            if (!n.get("extension").isArray()) {
                throw bad(where + ": extension must be an array");
            }
            for (JsonNode ext : n.get("extension")) {
                String url = textOrNull(ext, "url");
                if (url == null || !urls.add(url)) {
                    throw bad(where + ": unknown or duplicate extension");
                }
                switch (url) {
                    case MIN_URL, MAX_URL -> {
                        if (!numeric) {
                            throw bad(where + ": min/max extensions only allowed on integer/decimal");
                        }
                        JsonNode v = ext.get(type == ItemType.INTEGER ? "valueInteger" : "valueDecimal");
                        boolean ok = v != null && (type == ItemType.INTEGER ? v.isIntegralNumber() : v.isNumber());
                        if (!ok) {
                            throw bad(where + ": min/max must be " + (type == ItemType.INTEGER ? "valueInteger" : "valueDecimal"));
                        }
                        if (url.equals(MIN_URL)) {
                            range[0] = v.decimalValue();
                        } else {
                            range[1] = v.decimalValue();
                        }
                    }
                    case DECIMALS_URL -> {
                        JsonNode v = ext.get("valueInteger");
                        if (type != ItemType.DECIMAL || v == null || !v.isIntegralNumber() || v.asInt() < 0) {
                            throw bad(where + ": maxDecimalPlaces requires a decimal item and valueInteger");
                        }
                        decimals[0] = v.asInt();
                    }
                    case UNIT_URL -> {
                        JsonNode v = ext.get("valueCoding");
                        if (!numeric || v == null || !v.isObject()) {
                            throw bad(where + ": questionnaire-unit requires an integer/decimal item and valueCoding");
                        }
                    }
                    default -> throw bad(where + ": unsupported extension");
                }
            }
        }
        if (numeric) {
            if (range[0] == null || range[1] == null) {
                throw bad(where + ": minValue and maxValue are required");
            }
            if (range[0].compareTo(range[1]) > 0) {
                throw bad(where + ": minValue must not exceed maxValue");
            }
        }
        if (type == ItemType.DECIMAL && decimals[0] == null) {
            throw bad(where + ": maxDecimalPlaces is required for decimal");
        }
    }

    private static EnableWhen parseEnableWhen(JsonNode arr, String where, Map<String, ParsedItem> earlier) {
        if (!arr.isArray() || arr.size() != 1) {
            throw bad(where + ": enableWhen must contain exactly one entry");
        }
        JsonNode ew = arr.get(0);
        if (!ew.isObject()) {
            throw bad(where + ": enableWhen entry must be an object");
        }
        checkKeys(ew, Set.of("question", "operator", "answerBoolean", "answerCoding"), where + " enableWhen");
        if (!"=".equals(textOrNull(ew, "operator"))) {
            throw bad(where + ": enableWhen operator must be =");
        }
        String question = textOrNull(ew, "question");
        ParsedItem source = question == null ? null : earlier.get(question);
        if (source == null) {
            throw bad(where + ": enableWhen question must be an earlier top-level item");
        }
        if (source.type() == ItemType.BOOLEAN) {
            if (!ew.has("answerBoolean") || !ew.get("answerBoolean").isBoolean() || ew.has("answerCoding")) {
                throw bad(where + ": enableWhen on a boolean requires answerBoolean");
            }
            return new EnableWhen(question, ew.get("answerBoolean").asBoolean(), null);
        } else if (source.type() == ItemType.CHOICE) {
            JsonNode ac = ew.get("answerCoding");
            String code = ac == null ? null : textOrNull(ac, "code");
            if (code == null || ew.has("answerBoolean") || source.option(code) == null) {
                throw bad(where + ": enableWhen on a choice requires answerCoding with a known option code");
            }
            return new EnableWhen(question, null, code);
        }
        throw bad(where + ": enableWhen source must be a boolean or choice item");
    }

    private static String[] bilingual(JsonNode parent, String field, String underscore, String where) {
        String fr = textOrNull(parent, field);
        if (fr == null || fr.isBlank()) {
            throw bad(where + ": " + field + " is required");
        }
        String en = englishTranslation(parent.get(underscore));
        if (en == null || en.isBlank()) {
            throw bad(where + ": English translation of " + field + " is required");
        }
        return new String[]{fr, en};
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

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static InvalidQuestionnaireException bad(String message) {
        return new InvalidQuestionnaireException(message);
    }
}
