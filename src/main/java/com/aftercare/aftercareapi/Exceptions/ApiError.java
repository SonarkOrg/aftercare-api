package com.aftercare.aftercareapi.Exceptions;

import java.util.Map;

/** Uniform error body for every 400, 403, 404, 409, 413, 415 and 429. {@code fieldErrors} is never null. */
public record ApiError(int status, String error, String message, Map<String, String> fieldErrors) {
}
