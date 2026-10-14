package com.aftercare.aftercareapi.Questionnaires;

import java.math.BigDecimal;

public sealed interface AnswerValue {

    record BoolAnswer(boolean value) implements AnswerValue {
    }

    record NumberAnswer(BigDecimal value) implements AnswerValue {
    }

    record TextAnswer(String value) implements AnswerValue {
    }

    record CodingAnswer(String system, String code) implements AnswerValue {
    }

    /** Display snapshot used for Alert.answerValue and the {value} placeholder (spec 5.7). */
    default String snapshot() {
        if (this instanceof BoolAnswer b) {
            return Boolean.toString(b.value());
        } else if (this instanceof NumberAnswer n) {
            return n.value().stripTrailingZeros().toPlainString();
        } else if (this instanceof TextAnswer t) {
            return t.value().length() > 255 ? t.value().substring(0, 255) : t.value();
        } else if (this instanceof CodingAnswer c) {
            return c.code();
        }
        throw new IllegalStateException();
    }
}
