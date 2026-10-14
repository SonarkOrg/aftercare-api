package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.QuestionnairePurpose;
import com.aftercare.aftercareapi.support.Seeds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleParserTests {

    private static String r(String linkId, String op, String value, String outcome) {
        return "[{\"linkId\":\"" + linkId + "\",\"operator\":\"" + op + "\"" + (value == null ? "" : ",\"value\":" + value)
                + ",\"outcome\":" + outcome + "}]";
    }

    private static final String ALERT = "{\"type\":\"ALERT\",\"severity\":\"HIGH\",\"message\":{\"fr\":\"a\",\"en\":\"b\"}}";
    private static final String REVIEW = "{\"type\":\"NEEDS_REVIEW\",\"message\":{\"fr\":\"a\",\"en\":\"b\"}}";

    private static void failsFollowUp(String rules) {
        ParsedQuestionnaire q = Seeds.ortho();
        assertThrows(InvalidQuestionnaireException.class,
                () -> RuleParser.parse(Seeds.j(rules), q, QuestionnairePurpose.FOLLOW_UP_CHECKIN));
    }

    private static void failsPre(String rules) {
        ParsedQuestionnaire q = Seeds.surgery();
        assertThrows(InvalidQuestionnaireException.class,
                () -> RuleParser.parse(Seeds.j(rules), q, QuestionnairePurpose.PRE_APPOINTMENT));
    }

    @Test void unknownLinkId() { failsFollowUp(r("NOPE", "equals-true", null, ALERT)); }
    @Test void displayLinkId() { failsFollowUp(r("INTRO", "equals-true", null, ALERT)); }
    @Test void geOnBoolean() { failsFollowUp(r("SWELLING", ">=", "1", ALERT)); }
    @Test void equalsTrueOnInteger() { failsFollowUp(r("PAIN", "equals-true", null, ALERT)); }
    @Test void equalsTrueWithValue() { failsFollowUp(r("SWELLING", "equals-true", "true", ALERT)); }
    @Test void inEmptyArray() { failsFollowUp(r("MOBILITY", "in", "[]", ALERT)); }
    @Test void inUnknownCode() { failsFollowUp(r("MOBILITY", "in", "[\"flying\"]", ALERT)); }
    @Test void ruleOnText() { failsPre(r("ANTICOAGULANT_NAME", "equals-true", null, REVIEW)); }
    @Test void alertWithoutSeverity() { failsFollowUp(r("SWELLING", "equals-true", null, "{\"type\":\"ALERT\",\"message\":{\"fr\":\"a\",\"en\":\"b\"}}")); }
    @Test void alertInPreAppointment() { failsPre(r("WILL_ATTEND", "equals-false", null, ALERT)); }
    @Test void needsReviewWithSeverity() {
        failsPre(r("WILL_ATTEND", "equals-false", null, "{\"type\":\"NEEDS_REVIEW\",\"severity\":\"HIGH\",\"message\":{\"fr\":\"a\",\"en\":\"b\"}}"));
    }
    @Test void needsReviewInFollowUp() { failsFollowUp(r("SWELLING", "equals-true", null, REVIEW)); }
    @Test void missingEnglishMessage() {
        failsFollowUp(r("SWELLING", "equals-true", null, "{\"type\":\"ALERT\",\"severity\":\"HIGH\",\"message\":{\"fr\":\"a\"}}"));
    }
    @Test void messageTooLong() {
        failsFollowUp(r("SWELLING", "equals-true", null,
                "{\"type\":\"ALERT\",\"severity\":\"HIGH\",\"message\":{\"fr\":\"a\",\"en\":\"" + "x".repeat(401) + "\"}}"));
    }
}
