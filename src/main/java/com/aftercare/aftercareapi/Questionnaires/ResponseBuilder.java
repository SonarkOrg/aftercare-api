package com.aftercare.aftercareapi.Questionnaires;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Map;

/** Builds a spec 5.5 QuestionnaireResponse body from a plain answer map (used by the local seeder). */
public final class ResponseBuilder {

    private ResponseBuilder() {
    }

    /** The item type picks value[X]; a String for a choice item is the option code. Unknown linkIds are an error. */
    public static ObjectNode build(ParsedQuestionnaire q, Map<String, Object> answers, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("resourceType", "QuestionnaireResponse");
        root.put("questionnaire", q.canonical());
        root.put("status", "completed");
        ArrayNode items = root.putArray("item");
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            ParsedItem def = q.item(e.getKey());
            if (def == null) {
                throw new IllegalArgumentException("Unknown linkId " + e.getKey());
            }
            ObjectNode item = items.addObject();
            item.put("linkId", def.linkId());
            ObjectNode ans = item.putArray("answer").addObject();
            Object v = e.getValue();
            switch (def.type()) {
                case BOOLEAN -> ans.put("valueBoolean", (Boolean) v);
                case INTEGER -> ans.put("valueInteger", ((Number) v).longValue());
                case DECIMAL -> ans.put("valueDecimal", new BigDecimal(v.toString()).doubleValue());
                case STRING, TEXT -> ans.put("valueString", (String) v);
                case CHOICE -> ans.putObject("valueCoding").put("code", (String) v);
                default -> throw new IllegalArgumentException("Display items take no answer");
            }
        }
        return root;
    }
}
