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

    public static ApiException forbidden(String error, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, error, message);
    }

    public static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "File exceeds 10 MB");
    }

    public static ApiException unsupportedMedia() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Only PDF, JPEG and PNG files are accepted");
    }

    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many messages; try again later");
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
