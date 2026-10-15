package com.aftercare.aftercareapi.Services;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/** Small shared text helpers. */
final class Texts {

    private Texts() {
    }

    /** NURSE_PRACTITIONER becomes "Nurse practitioner". */
    static String humanize(Enum<?> e) {
        String s = e.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** First 140 characters, plus an ellipsis when cut. */
    static String preview(String body) {
        if (body.length() <= 140) {
            return body;
        }
        int cut = Character.isHighSurrogate(body.charAt(139)) ? 139 : 140;
        return body.substring(0, cut) + "…";
    }

    /** Accent-insensitive, case-insensitive French-Canadian ordering. */
    static Comparator<String> collation() {
        Collator c = Collator.getInstance(Locale.forLanguageTag("fr-CA"));
        c.setStrength(Collator.PRIMARY);
        return c::compare;
    }
}
