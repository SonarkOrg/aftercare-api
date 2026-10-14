package com.aftercare.aftercareapi.Questionnaires;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;

/** Validated answers keyed by linkId, in questionnaire order. */
public record ValidatedResponse(Map<String, AnswerValue> answers) {

    /** Builds the normalized stored QuestionnaireResponse (spec 5.6). Never contains patient identifiers. */
    public ObjectNode toFhir(ParsedQuestionnaire q, Instant authored, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("resourceType", "QuestionnaireResponse");
        root.put("questionnaire", q.canonical());
        root.put("status", "completed");
        root.put("authored", authored.toString());
        ArrayNode items = root.putArray("item");
        for (ParsedItem item : q.items()) {
            AnswerValue a = answers.get(item.linkId());
            if (a == null) {
                continue;
            }
            ObjectNode in = items.addObject();
            in.put("linkId", item.linkId());
            ObjectNode ans = in.putArray("answer").addObject();
            if (a instanceof AnswerValue.BoolAnswer b) {
                ans.put("valueBoolean", b.value());
            } else if (a instanceof AnswerValue.NumberAnswer n) {
                if (item.type() == ItemType.INTEGER) {
                    ans.put("valueInteger", n.value().longValueExact());
                } else {
                    ans.set("valueDecimal", DecimalNode.valueOf(n.value()));
                }
            } else if (a instanceof AnswerValue.TextAnswer t) {
                ans.put("valueString", t.value());
            } else if (a instanceof AnswerValue.CodingAnswer c) {
                ObjectNode coding = ans.putObject("valueCoding");
                coding.put("system", c.system());
                coding.put("code", c.code());
                coding.put("display", item.option(c.code()).displayFr());
            }
        }
        return root;
    }
}
