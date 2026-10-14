package com.aftercare.aftercareapi.Exceptions;

import java.util.Map;

/** Uniform error body for every 400, 404 and 409. {@code fieldErrors} is never null. */
public record ApiError(int status, String error, String message, Map<String, String> fieldErrors) {
}
