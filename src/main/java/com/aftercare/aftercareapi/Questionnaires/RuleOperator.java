package com.aftercare.aftercareapi.Questionnaires;

import java.util.Optional;

public enum RuleOperator {
    GTE(">="), LTE("<="), EQ("=="), EQUALS_TRUE("equals-true"), EQUALS_FALSE("equals-false"), IN("in");

    private final String symbol;

    RuleOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public static Optional<RuleOperator> fromSymbol(String s) {
        for (RuleOperator o : values()) {
            if (o.symbol.equals(s)) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }
}
