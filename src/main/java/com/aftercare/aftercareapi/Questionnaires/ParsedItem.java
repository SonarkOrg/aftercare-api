package com.aftercare.aftercareapi.Questionnaires;

import java.math.BigDecimal;
import java.util.List;

public record ParsedItem(String linkId, ItemType type, String textFr, String textEn, boolean required,
                         Integer maxLength, BigDecimal min, BigDecimal max, Integer maxDecimalPlaces,
                         List<AnswerOption> options, EnableWhen enableWhen) {

    public AnswerOption option(String code) {
        return options.stream().filter(o -> o.code().equals(code)).findFirst().orElse(null);
    }
}
