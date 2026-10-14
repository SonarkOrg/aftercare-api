package com.aftercare.aftercareapi.Exceptions;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Business exception translated to an {@link ApiError} by the GlobalExceptionHandler. Messages must never contain PHI. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String error, String message) {
        this(status, error, message, Map.of());
    }

    public ApiException(HttpStatus status, String error, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.error = error;
        this.fieldErrors = fieldErrors == null ? Map.of() : fieldErrors;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String error, String message) {
        return new ApiException(HttpStatus.CONFLICT, error, message);
    }

    public static ApiException invalidState(String message) {
        return conflict("INVALID_STATE", message);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", Map.of(field, message));
    }

    public static ApiException validation(Map<String, String> fieldErrors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", fieldErrors);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
