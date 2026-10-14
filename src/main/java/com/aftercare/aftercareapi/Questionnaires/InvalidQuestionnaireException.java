package com.aftercare.aftercareapi.Questionnaires;

/** Thrown when a questionnaire file violates the supported FHIR subset or rule format. Fails startup. */
public class InvalidQuestionnaireException extends RuntimeException {
    public InvalidQuestionnaireException(String message) {
        super(message);
    }
}
