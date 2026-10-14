package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.support.Seeds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleEvaluatorTests {

    private final RuleEvaluator ev = new RuleEvaluator();

    private static Rule rule(String id, RuleOperator op, String value, List<String> codes, String fr, String en) {
        return new Rule(id, op, value == null ? null : new BigDecimal(value), codes, RuleOutcome.ALERT, AlertSeverity.MEDIUM, fr, en);
    }

    private static ValidatedResponse resp(Object... kv) {
        Map<String, AnswerValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            AnswerValue a = v instanceof Boolean b ? new AnswerValue.BoolAnswer(b)
                    : v instanceof String s ? new AnswerValue.CodingAnswer("sys", s)
                    : new AnswerValue.NumberAnswer(new BigDecimal(v.toString()));
            m.put((String) kv[i], a);
        }
        return new ValidatedResponse(m);
    }

    private int hits(Rule r, ValidatedResponse resp) {
        return ev.evaluate(List.of(r), resp).size();
    }

    @Test
    void operatorsAtBoundaries() {
        Rule ge = rule("X", RuleOperator.GTE, "5", List.of(), "f", "e");
        assertEquals(1, hits(ge, resp("X", 5)));
        assertEquals(0, hits(ge, resp("X", 4)));
        Rule le = rule("X", RuleOperator.LTE, "5", List.of(), "f", "e");
        assertEquals(1, hits(le, resp("X", 5)));
        assertEquals(0, hits(le, resp("X", 6)));
        Rule eq = rule("X", RuleOperator.EQ, "37.5", List.of(), "f", "e");
        assertEquals(1, hits(eq, resp("X", new BigDecimal("37.50"))));
        assertEquals(0, hits(eq, resp("X", new BigDecimal("37.4"))));
        assertEquals(1, hits(rule("X", RuleOperator.EQUALS_TRUE, null, List.of(), "f", "e"), resp("X", true)));
        assertEquals(0, hits(rule("X", RuleOperator.EQUALS_TRUE, null, List.of(), "f", "e"), resp("X", false)));
        assertEquals(1, hits(rule("X", RuleOperator.EQUALS_FALSE, null, List.of(), "f", "e"), resp("X", false)));
        assertEquals(0, hits(rule("X", RuleOperator.EQUALS_FALSE, null, List.of(), "f", "e"), resp("X", true)));
        Rule in = rule("X", RuleOperator.IN, null, List.of("a", "b"), "f", "e");
        assertEquals(1, hits(in, resp("X", "b")));
        assertEquals(0, hits(in, resp("X", "c")));
    }

    @Test
    void firstMatchPerLinkIdWinsAndOrderIsRuleOrder() {
        Rule high = rule("X", RuleOperator.GTE, "7", List.of(), "high", "HIGH");
        Rule med = rule("X", RuleOperator.GTE, "5", List.of(), "med", "MED");
        Rule other = rule("Y", RuleOperator.EQUALS_TRUE, null, List.of(), "y", "Y");
        List<RuleHit> r = ev.evaluate(List.of(high, med, other), resp("Y", true, "X", 8));
        assertEquals(List.of("X", "Y"), r.stream().map(RuleHit::linkId).toList());
        assertEquals("HIGH", r.get(0).messageEn());
    }

    @Test
    void unansweredNeverFires() {
        assertEquals(0, hits(rule("X", RuleOperator.EQUALS_FALSE, null, List.of(), "f", "e"), resp()));
    }

    @Test
    void valueSubstitutedAndSnapshotFollowsSpec() {
        Rule r = rule("X", RuleOperator.GTE, "1", List.of(), "{value} deg", "{value} degrees");
        RuleHit h = ev.evaluate(List.of(r), resp("X", new BigDecimal("38.0"))).get(0);
        assertEquals("38", h.answerSnapshot());
        assertEquals("38 degrees", h.messageEn());
        assertEquals("38 deg", h.messageFr());
        Rule in = rule("M", RuleOperator.IN, null, List.of("bedbound"), "{value}", "{value}");
        assertEquals("bedbound", ev.evaluate(List.of(in), resp("M", "bedbound")).get(0).answerSnapshot());
    }

    private List<RuleHit> seed(ParsedQuestionnaire q, Object... kv) {
        return ev.evaluate(q.rules(), resp(kv));
    }

    @Test
    void seedPainThresholds() {
        ParsedQuestionnaire q = Seeds.visceral();
        assertEquals(0, seed(q, "PAIN", 4).size());
        assertEquals(AlertSeverity.MEDIUM, seed(q, "PAIN", 5).get(0).severity());
        assertEquals(AlertSeverity.MEDIUM, seed(q, "PAIN", 6).get(0).severity());
        assertEquals(AlertSeverity.HIGH, seed(q, "PAIN", 7).get(0).severity());
    }

    @Test
    void seedTemperatureThresholds() {
        ParsedQuestionnaire q = Seeds.visceral();
        assertEquals(0, seed(q, "TEMPERATURE", new BigDecimal("37.4")).size());
        assertEquals(AlertSeverity.MEDIUM, seed(q, "TEMPERATURE", new BigDecimal("37.5")).get(0).severity());
        assertEquals(AlertSeverity.HIGH, seed(q, "TEMPERATURE", new BigDecimal("38.0")).get(0).severity());
    }

    @Test
    void seedMobilityBedbound() {
        ParsedQuestionnaire q = Seeds.ortho();
        List<RuleHit> r = seed(q, "MOBILITY", "bedbound");
        assertEquals(AlertSeverity.MEDIUM, r.get(0).severity());
        assertEquals(0, seed(q, "MOBILITY", "unaided").size());
    }
}
