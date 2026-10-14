package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.support.Seeds;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.aftercare.aftercareapi.support.Seeds.j;
import static org.junit.jupiter.api.Assertions.*;

class ResponseValidatorTests {

    private final ResponseValidator validator = new ResponseValidator();
    private final ParsedQuestionnaire v = Seeds.visceral();
    private static final String URL = "https://aftercare.example/fhir/Questionnaire/followup-visceral-surgery|1";

    private static String booleans(String... skip) {
        StringBuilder sb = new StringBuilder();
        for (String id : new String[]{"WOUND_REDNESS", "WOUND_DISCHARGE", "NAUSEA_VOMITING", "ABLE_TO_EAT", "SHORTNESS_OF_BREATH"}) {
            if (java.util.Arrays.asList(skip).contains(id)) {
                continue;
            }
            sb.append(",{\"linkId\":\"").append(id).append("\",\"answer\":[{\"valueBoolean\":false}]}");
        }
        return sb.toString();
    }

    private static String body(String extraItems, String header) {
        return "{" + header + ",\"item\":[{\"linkId\":\"PAIN\",\"answer\":[{\"valueInteger\":3}]}" + extraItems + "]}";
    }

    private static final String OK_HEADER = "\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"" + URL + "\",\"status\":\"completed\"";

    private Map<String, String> errors(ParsedQuestionnaire q, String body) {
        ApiException e = assertThrows(ApiException.class, () -> validator.validate(q, j(body)));
        assertEquals("VALIDATION_FAILED", e.getError());
        return e.getFieldErrors();
    }

    private Map<String, String> errors(String body) {
        return errors(v, body);
    }

    private Map<String, String> itemError(String itemJson) {
        return errors(body(itemJson + booleans(), OK_HEADER));
    }

    @Test
    void validResponseKeepsQuestionnaireOrder() {
        String b = "{" + OK_HEADER + ",\"item\":[" + "{\"linkId\":\"WOUND_REDNESS\",\"answer\":[{\"valueBoolean\":true}]},"
                + "{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":37.2}]},{\"linkId\":\"PAIN\",\"answer\":[{\"valueInteger\":3}]}"
                + booleans("WOUND_REDNESS") + "]}";
        ValidatedResponse r = validator.validate(v, j(b));
        assertEquals(java.util.List.of("PAIN", "TEMPERATURE", "WOUND_REDNESS", "WOUND_DISCHARGE", "NAUSEA_VOMITING", "ABLE_TO_EAT", "SHORTNESS_OF_BREATH"),
                java.util.List.copyOf(r.answers().keySet()));
    }

    @Test void wrongResourceType() {
        assertEquals("must be QuestionnaireResponse", errors(body(booleans(), OK_HEADER.replace("\"QuestionnaireResponse\"", "\"Other\""))).get("resourceType"));
    }
    @Test void wrongQuestionnaire() {
        assertEquals("does not match the assigned questionnaire", errors(body(booleans(), OK_HEADER.replace("|1", "|2"))).get("questionnaire"));
    }
    @Test void wrongStatus() {
        assertEquals("must be completed", errors(body(booleans(), OK_HEADER.replace("completed", "in-progress"))).get("status"));
    }
    @Test void itemMissing() {
        assertEquals("is required", errors("{" + OK_HEADER + "}").get("item"));
    }
    @Test void nestedItemAndDuplicate() {
        assertEquals("invalid structure", itemError(",{\"linkId\":\"TEMPERATURE\",\"item\":[],\"answer\":[{\"valueDecimal\":37}]}").get("item.TEMPERATURE"));
        assertEquals("invalid structure", itemError(",{\"linkId\":\"PAIN\",\"answer\":[{\"valueInteger\":3}]}").get("item.PAIN"));
    }
    @Test void unknownAndDisplayLinkId() {
        assertEquals("unknown question", itemError(",{\"linkId\":\"NOPE\",\"answer\":[{\"valueInteger\":3}]}").get("item.NOPE"));
        assertEquals("unknown question", itemError(",{\"linkId\":\"INTRO\",\"answer\":[{\"valueInteger\":3}]}").get("item.INTRO"));
    }
    @Test void answerCount() {
        assertEquals("must have exactly one answer", itemError(",{\"linkId\":\"TEMPERATURE\"}").get("item.TEMPERATURE"));
        assertEquals("must have exactly one answer", itemError(",{\"linkId\":\"TEMPERATURE\",\"answer\":[]}").get("item.TEMPERATURE"));
        assertEquals("must have exactly one answer",
                itemError(",{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":37},{\"valueDecimal\":38}]}").get("item.TEMPERATURE"));
    }
    @Test void requiredMissing() {
        assertEquals("is required", errors(body("", OK_HEADER)).get("item.WOUND_REDNESS"));
        assertEquals("is required", errors("{" + OK_HEADER + ",\"item\":[]}").get("item.PAIN"));
    }
    @Test void booleanType() {
        Map<String, String> e = errors(body(booleans("WOUND_REDNESS") + ",{\"linkId\":\"WOUND_REDNESS\",\"answer\":[{\"valueBoolean\":\"yes\"}]}", OK_HEADER));
        assertEquals("must be true or false", e.get("item.WOUND_REDNESS"));
    }
    @Test void integerType() {
        assertEquals("must be an integer", errors("{" + OK_HEADER + ",\"item\":[{\"linkId\":\"PAIN\",\"answer\":[{\"valueInteger\":3.5}]}" + booleans() + "]}").get("item.PAIN"));
    }
    @Test void decimalType() {
        assertEquals("must be a number", itemError(",{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":\"hot\"}]}").get("item.TEMPERATURE"));
    }
    @Test void rangeAndDecimalPlaces() {
        assertEquals("must be between 34 and 42", itemError(",{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":50}]}").get("item.TEMPERATURE"));
        assertEquals("must have at most 1 decimal", itemError(",{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":37.25}]}").get("item.TEMPERATURE"));
        assertNull(validator.validate(v, j(body(",{\"linkId\":\"TEMPERATURE\",\"answer\":[{\"valueDecimal\":37.50}]}" + booleans(), OK_HEADER))).answers().get("NOPE"));
    }
    @Test void integerRange() {
        assertEquals("must be between 0 and 10", errors("{" + OK_HEADER + ",\"item\":[{\"linkId\":\"PAIN\",\"answer\":[{\"valueInteger\":11}]}" + booleans() + "]}").get("item.PAIN"));
    }

    @Test void messagesNeverEchoValues() {
        Map<String, String> e = errors("{" + OK_HEADER + ",\"item\":[{\"linkId\":\"PAIN\",\"answer\":[{\"valueInteger\":99}]}" + booleans() + "]}");
        e.values().forEach(m -> assertFalse(m.contains("99")));
    }

    @Test void stringTextAndChoice() {
        ParsedQuestionnaire s = Seeds.surgery();
        String h = "\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://aftercare.example/fhir/Questionnaire/pre-surgery|1\",\"status\":\"completed\"";
        String required = "{\"linkId\":\"WILL_ATTEND\",\"answer\":[{\"valueBoolean\":true}]},{\"linkId\":\"TAKES_ANTICOAGULANT\",\"answer\":[{\"valueBoolean\":false}]},"
                + "{\"linkId\":\"RECENT_ILLNESS\",\"answer\":[{\"valueBoolean\":false}]},{\"linkId\":\"FASTING_ACKNOWLEDGED\",\"answer\":[{\"valueBoolean\":true}]},"
                + "{\"linkId\":\"RIDE_HOME\",\"answer\":[{\"valueBoolean\":true}]},{\"linkId\":\"CONSENT_READ\",\"answer\":[{\"valueBoolean\":true}]},"
                + "{\"linkId\":\"NEEDS_INTERPRETER\",\"answer\":[{\"valueBoolean\":false}]}";
        assertEquals("must be text", errors(s, "{" + h + ",\"item\":[" + required + ",{\"linkId\":\"ALLERGIES\",\"answer\":[{\"valueString\":\"  \"}]}]}").get("item.ALLERGIES"));
        assertEquals("must be text", errors(s, "{" + h + ",\"item\":[" + required + ",{\"linkId\":\"ALLERGIES\",\"answer\":[{\"valueInteger\":1}]}]}").get("item.ALLERGIES"));
        assertEquals("must be at most 1000 characters", errors(s, "{" + h + ",\"item\":[" + required + ",{\"linkId\":\"ALLERGIES\",\"answer\":[{\"valueString\":\"" + "x".repeat(1001) + "\"}]}]}").get("item.ALLERGIES"));

        ParsedQuestionnaire o = Seeds.ortho();
        String oh = "\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://aftercare.example/fhir/Questionnaire/followup-orthopedic-surgery|1\",\"status\":\"completed\"";
        String reqd = "{\"linkId\":\"PAIN\",\"answer\":[{\"valueInteger\":1}]}" + ",{\"linkId\":\"WOUND_REDNESS\",\"answer\":[{\"valueBoolean\":false}]},{\"linkId\":\"WOUND_DISCHARGE\",\"answer\":[{\"valueBoolean\":false}]},"
                + "{\"linkId\":\"SWELLING\",\"answer\":[{\"valueBoolean\":false}]},{\"linkId\":\"CALF_PAIN\",\"answer\":[{\"valueBoolean\":false}]},{\"linkId\":\"NUMBNESS\",\"answer\":[{\"valueBoolean\":false}]},"
                + "{\"linkId\":\"SHORTNESS_OF_BREATH\",\"answer\":[{\"valueBoolean\":false}]}";
        assertEquals("must be one of the options", errors(o, "{" + oh + ",\"item\":[" + reqd + ",{\"linkId\":\"MOBILITY\",\"answer\":[{\"valueCoding\":{\"code\":\"flying\"}}]}]}").get("item.MOBILITY"));
        ValidatedResponse ok = validator.validate(o, j("{" + oh + ",\"item\":[" + reqd + ",{\"linkId\":\"MOBILITY\",\"answer\":[{\"valueCoding\":{\"system\":\"evil\",\"code\":\"bedbound\"}}]}]}"));
        assertEquals(new AnswerValue.CodingAnswer("https://aftercare.example/fhir/CodeSystem/mobility", "bedbound"), ok.answers().get("MOBILITY"));
    }

    @Test void enableWhenBehaviour() {
        ParsedQuestionnaire s = Seeds.surgery();
        String h = "\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://aftercare.example/fhir/Questionnaire/pre-surgery|1\",\"status\":\"completed\"";
        String base = "{\"linkId\":\"WILL_ATTEND\",\"answer\":[{\"valueBoolean\":true}]},"
                + "{\"linkId\":\"RECENT_ILLNESS\",\"answer\":[{\"valueBoolean\":false}]},{\"linkId\":\"FASTING_ACKNOWLEDGED\",\"answer\":[{\"valueBoolean\":true}]},"
                + "{\"linkId\":\"RIDE_HOME\",\"answer\":[{\"valueBoolean\":true}]},{\"linkId\":\"CONSENT_READ\",\"answer\":[{\"valueBoolean\":true}]},"
                + "{\"linkId\":\"NEEDS_INTERPRETER\",\"answer\":[{\"valueBoolean\":false}]}";
        String yes = ",{\"linkId\":\"TAKES_ANTICOAGULANT\",\"answer\":[{\"valueBoolean\":true}]}";
        String no = ",{\"linkId\":\"TAKES_ANTICOAGULANT\",\"answer\":[{\"valueBoolean\":false}]}";
        String name = ",{\"linkId\":\"ANTICOAGULANT_NAME\",\"answer\":[{\"valueString\":\"X\"}]}";
        assertEquals("is required", errors(s, "{" + h + ",\"item\":[" + base + yes + "]}").get("item.ANTICOAGULANT_NAME"));
        assertEquals("question is not enabled", errors(s, "{" + h + ",\"item\":[" + base + no + name + "]}").get("item.ANTICOAGULANT_NAME"));
        assertDoesNotThrow(() -> validator.validate(s, j("{" + h + ",\"item\":[" + base + no + "]}")));
        // source unanswered: TAKES_ANTICOAGULANT is required itself, but the dependent is not also reported as required
        Map<String, String> e = errors(s, "{" + h + ",\"item\":[" + base + "]}");
        assertEquals("is required", e.get("item.TAKES_ANTICOAGULANT"));
        assertNull(e.get("item.ANTICOAGULANT_NAME"));
        assertDoesNotThrow(() -> validator.validate(s, j("{" + h + ",\"item\":[" + base + yes + name + "]}")));
    }
}
