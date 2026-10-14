package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Models.Dto.PublicSessionDTO;
import com.aftercare.aftercareapi.Models.Dto.PublicSubmitResultDTO;
import com.aftercare.aftercareapi.Services.PublicQuestionnaireService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Patient endpoints authenticated only by the access code in the path. Never accepts or requires a JWT. */
@RestController
public class PublicQuestionnaireController {

    private final PublicQuestionnaireService service;

    public PublicQuestionnaireController(PublicQuestionnaireService service) {
        this.service = service;
    }

    @GetMapping("/api/public/q/{code}")
    public ResponseEntity<PublicSessionDTO> get(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(code));
    }

    @PostMapping("/api/public/q/{code}")
    public ResponseEntity<PublicSubmitResultDTO> submit(@PathVariable String code, @RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.submit(code, body));
    }
}
