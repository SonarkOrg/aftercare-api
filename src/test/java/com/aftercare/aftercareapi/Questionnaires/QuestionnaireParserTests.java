package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.support.Seeds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class QuestionnaireParserTests {

    private static final String F = "followup-visceral-surgery.v1.json";

    private static ObjectNode q() {
        return Seeds.questionnaire(F);
    }

    private static ObjectNode item(ObjectNode q, String linkId) {
        for (JsonNode i : q.get("item")) {
            if (i.get("linkId").asText().equals(linkId)) {
                return (ObjectNode) i;
            }
        }
        throw new IllegalArgumentException(linkId);
    }

    private static void fails(Consumer<ObjectNode> mutate) {
        ObjectNode q = q();
        mutate.accept(q);
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(q));
    }

    private static ObjectNode ew(String question, String op) {
        ObjectNode e = Seeds.OM.createObjectNode();
        e.put("question", question);
        e.put("operator", op);
        e.put("answerBoolean", true);
        return e;
    }

    @Test
    void allSeedFilesParse() {
        for (String f : new String[]{F, "followup-orthopedic-surgery.v1.json", "pre-surgery.v1.json", "pre-consult.v1.json"}) {
            assertNotNull(Seeds.parsed(f));
        }
    }

    @Test
    void localizeReturnsFrenchOrEnglish() {
        ObjectNode q = q();
        ObjectNode pain = item(q, "PAIN");
        assertTrue(QuestionnaireParser.localize(pain.get("text").asText(), pain.get("_text"), "fr").startsWith("Sur une"));
        assertEquals("On a scale of 0 to 10, how bad is your pain today?",
                QuestionnaireParser.localize(pain.get("text").asText(), pain.get("_text"), "en"));
        assertEquals("base", QuestionnaireParser.localize("base", null, "en"));
    }

    @Test void wrongResourceType() { fails(q -> q.put("resourceType", "Other")); }
    @Test void missingUrl() { fails(q -> q.remove("url")); }
    @Test void nonDigitVersion() { fails(q -> q.put("version", "1.0")); }
    @Test void badStatus() { fails(q -> q.put("status", "draft")); }
    @Test void languageNotFrCa() { fails(q -> q.put("language", "en")); }
    @Test void missingTitleTranslation() { fails(q -> q.remove("_title")); }
    @Test void missingItemTranslation() { fails(q -> item(q, "PAIN").remove("_text")); }
    @Test void unsupportedTopLevelElement() { fails(q -> q.put("copyright", "x")); }

    @Test
    void missingOptionTranslation() {
        ObjectNode q = Seeds.questionnaire("followup-orthopedic-surgery.v1.json");
        ((ObjectNode) item(q, "MOBILITY").get("answerOption").get(0).get("valueCoding")).remove("_display");
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(q));
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "date", "quantity", "open-choice", "attachment"})
    void unsupportedType(String type) { fails(q -> item(q, "WOUND_REDNESS").put("type", type)); }

    @Test void duplicateLinkId() { fails(q -> item(q, "WOUND_DISCHARGE").put("linkId", "PAIN")); }
    @Test void duplicateLinkIdWithNested() { fails(q -> ((ObjectNode) item(q, "PAIN").get("item").get(0)).put("linkId", "TEMPERATURE")); }
    @Test void repeatsTrue() { fails(q -> item(q, "WOUND_REDNESS").put("repeats", true)); }
    @Test void requiredOnDisplay() { fails(q -> item(q, "INTRO").put("required", true)); }
    @Test void integerWithoutMinMax() { fails(q -> item(q, "PAIN").remove("extension")); }
    @Test void decimalWithoutMaxDecimalPlaces() {
        fails(q -> ((ArrayNode) item(q, "TEMPERATURE").get("extension")).remove(2));
    }
    @Test void maxLengthOnBoolean() { fails(q -> item(q, "WOUND_REDNESS").put("maxLength", 10)); }
    @Test void unknownExtension() {
        fails(q -> ((ObjectNode) item(q, "PAIN").get("extension").get(0)).put("url", "http://example.org/x"));
    }
    @Test void nestedNonDisplay() { fails(q -> ((ObjectNode) item(q, "PAIN").get("item").get(0)).put("type", "boolean")); }
    @Test void displayWithNestedItems() {
        fails(q -> item(q, "INTRO").set("item", item(q, "PAIN").get("item").deepCopy()));
    }

    @Test
    void stringWithoutMaxLengthOrTooLong() {
        ObjectNode a = Seeds.questionnaire("pre-surgery.v1.json");
        item(a, "ANTICOAGULANT_NAME").remove("maxLength");
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(a));
        ObjectNode b = Seeds.questionnaire("pre-surgery.v1.json");
        item(b, "ANTICOAGULANT_NAME").put("maxLength", 1001);
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(b));
    }

    @Test
    void choiceWithoutOptionsOrDuplicateCodes() {
        ObjectNode a = Seeds.questionnaire("followup-orthopedic-surgery.v1.json");
        item(a, "MOBILITY").remove("answerOption");
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(a));
        ObjectNode b = Seeds.questionnaire("followup-orthopedic-surgery.v1.json");
        ((ObjectNode) item(b, "MOBILITY").get("answerOption").get(1).get("valueCoding")).put("code", "unaided");
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(b));
    }

    private static ObjectNode surgeryQ() {
        return Seeds.questionnaire("pre-surgery.v1.json");
    }

    private static void ewFails(Consumer<ObjectNode> mutate) {
        ObjectNode q = surgeryQ();
        mutate.accept(item(q, "ANTICOAGULANT_NAME"));
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(q));
    }

    @Test void enableWhenTwoEntries() {
        ewFails(i -> ((ArrayNode) i.get("enableWhen")).add(ew("TAKES_ANTICOAGULANT", "=")));
    }
    @Test void enableWhenBadOperator() {
        ewFails(i -> ((ObjectNode) i.get("enableWhen").get(0)).put("operator", "!="));
    }
    @Test void enableWhenUnknownSource() {
        ewFails(i -> ((ObjectNode) i.get("enableWhen").get(0)).put("question", "NOPE"));
    }
    @Test void enableWhenLaterSource() {
        ewFails(i -> ((ObjectNode) i.get("enableWhen").get(0)).put("question", "RIDE_HOME"));
    }
    @Test void enableWhenWithEnableBehavior() { ewFails(i -> i.put("enableBehavior", "all")); }

    @Test
    void enableWhenSourceOfWrongType() {
        ObjectNode q = Seeds.questionnaire(F);
        ArrayNode ews = Seeds.OM.createArrayNode().add(ew("PAIN", "="));
        item(q, "TEMPERATURE").set("enableWhen", ews);
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(q));
    }

    @Test
    void enableWhenCodingNotInOptions() {
        ObjectNode q = Seeds.questionnaire("followup-orthopedic-surgery.v1.json");
        ObjectNode e = Seeds.OM.createObjectNode();
        e.put("question", "MOBILITY");
        e.put("operator", "=");
        e.putObject("answerCoding").put("code", "flying");
        item(q, "SHORTNESS_OF_BREATH").set("enableWhen", Seeds.OM.createArrayNode().add(e));
        assertThrows(InvalidQuestionnaireException.class, () -> QuestionnaireParser.parse(q));
    }
}
