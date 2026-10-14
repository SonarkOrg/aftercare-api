package com.aftercare.aftercareapi.Questionnaires;

import com.aftercare.aftercareapi.Enums.QuestionnaireStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed, validated view of a stored Questionnaire (top-level items only) plus its rules. */
public record ParsedQuestionnaire(String url, String version, QuestionnaireStatus status, String titleFr, String titleEn,
                                  List<ParsedItem> items, List<Rule> rules) {

    public String canonical() {
        return url + "|" + version;
    }

    public ParsedItem item(String linkId) {
        for (ParsedItem i : items) {
            if (i.linkId().equals(linkId)) {
                return i;
            }
        }
        return null;
    }

    /** English label of an item, falling back to the linkId. */
    public String labelEn(String linkId) {
        ParsedItem i = item(linkId);
        return i == null ? linkId : i.textEn();
    }

    public ParsedQuestionnaire withRules(List<Rule> newRules) {
        return new ParsedQuestionnaire(url, version, status, titleFr, titleEn, items, List.copyOf(newRules));
    }
}
