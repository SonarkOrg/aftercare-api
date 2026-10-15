package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Exceptions.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Collects field errors for the common text rules (trim, blank, length, plain text). Messages never echo values. */
final class Fields {

    static final Pattern PHONE = Pattern.compile("^[0-9+()\\-. ]{7,25}$");
    static final Pattern POSTAL = Pattern.compile("^[A-Za-z]\\d[A-Za-z][ -]?\\d[A-Za-z]\\d$");
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    private final Map<String, String> errors = new LinkedHashMap<>();

    void error(String field, String message) {
        errors.putIfAbsent(field, message);
    }

    /** Required single-line or free text: trimmed, not blank, at most max characters. */
    String required(String field, String value, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            error(field, "must not be blank");
            return null;
        }
        if (v.length() > max) {
            error(field, "must be at most " + max + " characters");
            return null;
        }
        return v;
    }

    /** Optional text: blank becomes null. */
    String optional(String field, String value, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > max) {
            error(field, "must be at most " + max + " characters");
            return null;
        }
        return v;
    }

    /** Required plain-text body: control characters rejected, CRLF normalized, trimmed, length-checked. */
    String body(String field, String value, int min, int max) {
        if (value != null && CONTROL.matcher(value).find()) {
            error(field, "must be plain text");
            return null;
        }
        String v = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (v.isEmpty()) {
            error(field, "must not be blank");
            return null;
        }
        if (v.length() < min || v.length() > max) {
            error(field, "must be between " + min + " and " + max + " characters");
            return null;
        }
        return v;
    }

    String phone(String field, String value, boolean required) {
        String v = required ? required(field, value, 25) : optional(field, value, 25);
        if (v == null) {
            return null;
        }
        long digits = v.chars().filter(Character::isDigit).count();
        if (!PHONE.matcher(v).matches() || digits < 7) {
            error(field, "must be a valid phone number");
            return null;
        }
        return v;
    }

    String postal(String field, String value) {
        String v = optional(field, value, 7);
        if (v == null) {
            return null;
        }
        if (!POSTAL.matcher(v).matches()) {
            error(field, "must be a valid postal code");
            return null;
        }
        return v.toUpperCase();
    }

    void check() {
        if (!errors.isEmpty()) {
            throw ApiException.validation(new LinkedHashMap<>(errors));
        }
    }
}
