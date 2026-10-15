package com.aftercare.aftercareapi.Exceptions;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates exceptions to {@link ApiError} for every 400, 403, 404, 409, 413, 415, 429. Messages never contain request
 * values or PHI.
 * There is deliberately no catch-all handler.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static ResponseEntity<ApiError> body(HttpStatus status, String error, String message, Map<String, String> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), error, message, fieldErrors));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return body(ex.getStatus(), ex.getError(), ex.getMessage(), ex.getFieldErrors());
    }

    // Only field names and constraint messages are returned, never the rejected values.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Malformed request body", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", Map.of(ex.getName(), "invalid value"));
    }

    // Generic body on purpose: constraint and SQL details must not leak to clients.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return body(HttpStatus.CONFLICT, "CONFLICT", "Request conflicts with existing data", Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "CONFLICT", "The record was modified concurrently; retry", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        ApiException e = ApiException.tooLarge();
        return body(e.getStatus(), e.getError(), e.getMessage(), Map.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", Map.of(ex.getRequestPartName(), "required"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", Map.of(ex.getParameterName(), "required"));
    }
}
