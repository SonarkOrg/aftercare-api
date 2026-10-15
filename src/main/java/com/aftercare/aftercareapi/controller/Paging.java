package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Exceptions.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared page/size validation: page >= 0, size 1..100. */
final class Paging {

    private Paging() {
    }

    static void check(int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (page < 0) {
            errors.put("page", "must be at least 0");
        }
        if (size < 1 || size > 100) {
            errors.put("size", "must be between 1 and 100");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }
}
