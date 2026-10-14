package com.aftercare.aftercareapi.Questionnaires;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generic, data-driven rule evaluation (spec 6.2). */
@Component
public class RuleEvaluator {

    public List<RuleHit> evaluate(List<Rule> rules, ValidatedResponse response) {
        List<RuleHit> hits = new ArrayList<>();
        Set<String> fired = new HashSet<>();
        for (Rule rule : rules) {
            if (fired.contains(rule.linkId())) {
                continue;
            }
            AnswerValue answer = response.answers().get(rule.linkId());
            if (answer == null || !matches(rule, answer)) {
                continue;
            }
            fired.add(rule.linkId());
            String snap = answer.snapshot();
            hits.add(new RuleHit(rule.linkId(), rule.outcome(), rule.severity(),
                    rule.messageEn().replace("{value}", snap), rule.messageFr().replace("{value}", snap), snap));
        }
        return hits;
    }

    private static boolean matches(Rule rule, AnswerValue answer) {
        switch (rule.operator()) {
            case GTE, LTE, EQ -> {
                if (!(answer instanceof AnswerValue.NumberAnswer n)) {
                    return false;
                }
                int c = n.value().compareTo(rule.value());
                return switch (rule.operator()) {
                    case GTE -> c >= 0;
                    case LTE -> c <= 0;
                    default -> c == 0;
                };
            }
            case EQUALS_TRUE -> {
                return answer instanceof AnswerValue.BoolAnswer b && b.value();
            }
            case EQUALS_FALSE -> {
                return answer instanceof AnswerValue.BoolAnswer b && !b.value();
            }
            case IN -> {
                return answer instanceof AnswerValue.CodingAnswer c && rule.codes().contains(c.code());
            }
        }
        return false;
    }
}
