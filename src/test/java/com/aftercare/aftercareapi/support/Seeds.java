package com.aftercare.aftercareapi.support;

import com.aftercare.aftercareapi.Enums.QuestionnairePurpose;
import com.aftercare.aftercareapi.Questionnaires.ParsedQuestionnaire;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireParser;
import com.aftercare.aftercareapi.Questionnaires.RuleParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class Seeds {
    public static final ObjectMapper OM = new ObjectMapper();

    private Seeds() {
    }

    public static JsonNode wrapper(String file) {
        try (InputStream in = Seeds.class.getResourceAsStream("/questionnaires/" + file)) {
            return OM.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deep copy of the Questionnaire resource of a seed file, for mutation. */
    public static ObjectNode questionnaire(String file) {
        return wrapper(file).get("questionnaire").deepCopy();
    }


    public static ParsedQuestionnaire parsed(String file) {
        JsonNode w = wrapper(file);
        ParsedQuestionnaire q = QuestionnaireParser.parse(w.get("questionnaire"));
        return q.withRules(RuleParser.parse(w.get("rules"), q, QuestionnairePurpose.valueOf(w.get("purpose").asText())));
    }

    public static ParsedQuestionnaire visceral() {
        return parsed("followup-visceral-surgery.v1.json");
    }

    public static ParsedQuestionnaire ortho() {
        return parsed("followup-orthopedic-surgery.v1.json");
    }

    public static ParsedQuestionnaire surgery() {
        return parsed("pre-surgery.v1.json");
    }

    public static ParsedQuestionnaire consult() {
        return parsed("pre-consult.v1.json");
    }

    public static JsonNode j(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Writes a mutated copy of a seed wrapper into dir and returns a file: pattern matching only that file. */
    public static String variant(Path dir, String seedFile, String outName, Consumer<ObjectNode> mutate) {
        try {
            ObjectNode w = wrapper(seedFile).deepCopy();
            mutate.accept(w);
            Files.writeString(dir.resolve(outName), OM.writeValueAsString(w));
            return "file:" + dir.toAbsolutePath() + "/" + outName;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
