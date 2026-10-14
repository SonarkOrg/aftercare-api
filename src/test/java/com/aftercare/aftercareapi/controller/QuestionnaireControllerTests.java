package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuestionnaireControllerTests extends IntegrationTest {

    @Test
    void listHasFourEntriesWithTitles() throws Exception {
        JsonNode list = json(call(get("/api/questionnaires")));
        assertEquals(4, list.size());
        for (JsonNode d : list) {
            assertFalse(d.get("title").get("en").asText().isBlank());
            assertFalse(d.get("title").get("fr").asText().isBlank());
            assertFalse(d.has("questionnaire"));
        }
        assertTrue(list.get(0).get("url").asText().compareTo(list.get(1).get("url").asText()) <= 0);
    }

    @Test
    void detailIncludesQuestionnaireObjectAndRules() throws Exception {
        long id = json(call(get("/api/questionnaires"))).get(0).get("id").asLong();
        JsonNode d = json(call(get("/api/questionnaires/" + id)));
        assertEquals("Questionnaire", d.get("questionnaire").get("resourceType").asText());
        assertTrue(d.get("rules").isArray());
        assertTrue(d.get("rules").size() > 0);
    }

    @Test
    void unknownIdIs404AndNoJwtIs401() throws Exception {
        assertEquals(404, call(get("/api/questionnaires/999999")).getResponse().getStatus());
        mockMvc.perform(get("/api/questionnaires")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/questionnaires/1")).andExpect(status().isUnauthorized());
    }
}
