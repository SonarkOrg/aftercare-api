package com.aftercare.aftercareapi.Questionnaires;

/** Single "=" condition. Exactly one of answerBoolean / answerCode is set. */
public record EnableWhen(String question, Boolean answerBoolean, String answerCode) {
}
