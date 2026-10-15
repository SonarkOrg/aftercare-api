package com.aftercare.aftercareapi.Services;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure RAMQ helpers: normalize, validate, mask. Never logs and never stores anything. */
public final class HealthNumbers {

    private static final Pattern SHAPE = Pattern.compile("^[A-Z]{4}\\d{8}$");

    private HealthNumbers() {
    }

    /** Upper-case, without spaces and hyphens. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }

    /** Four letters, eight digits; digits 3-4 in 01-12 or 51-62 (month), digits 5-6 in 01-31 (day). */
    public static boolean isValid(String normalized) {
        if (normalized == null || !SHAPE.matcher(normalized).matches()) {
            return false;
        }
        String digits = normalized.substring(4);
        int month = Integer.parseInt(digits.substring(2, 4));
        int day = Integer.parseInt(digits.substring(4, 6));
        boolean monthOk = (month >= 1 && month <= 12) || (month >= 51 && month <= 62);
        return monthOk && day >= 1 && day <= 31;
    }

    /** TREM68541212 becomes "TREM •••• ••12". */
    public static String mask(String normalized) {
        return normalized.substring(0, 4) + " •••• ••" + normalized.substring(normalized.length() - 2);
    }
}
