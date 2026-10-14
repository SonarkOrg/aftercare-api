package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.Dto.QuestionnaireDefinitionDTO;
import com.aftercare.aftercareapi.Models.Dto.QuestionnaireDefinitionDetailDTO;
import com.aftercare.aftercareapi.Services.QuestionnaireDefinitionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class QuestionnaireController {

    private final QuestionnaireDefinitionService service;

    public QuestionnaireController(QuestionnaireDefinitionService service) {
        this.service = service;
    }

    @GetMapping("/api/questionnaires")
    public List<QuestionnaireDefinitionDTO> list() {
        return service.list();
    }

    @GetMapping("/api/questionnaires/{id:\\d+}")
    public QuestionnaireDefinitionDetailDTO detail(@PathVariable Long id) {
        return service.detail(id);
    }
}
