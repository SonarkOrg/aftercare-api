package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.NotificationChannel;
import com.aftercare.aftercareapi.Models.Patient;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Recipient normalisation and masking. Masked values are the only recipient data stored in the outbox. */
public final class Recipients {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private Recipients() {
    }

    /** Trimmed, lower-cased, well-formed address, else empty. */
    public static Optional<String> email(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String e = raw.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(e).matches() && e.length() <= 255 ? Optional.of(e) : Optional.empty();
    }

    /**
     * E.164 from a phone as typed: everything except digits and a leading '+' is dropped. Ten digits get +1, eleven digits
     * starting with 1 get '+', an explicit '+' with 8-15 digits is kept; anything else is invalid.
     */
    public static Optional<String> phoneE164(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        boolean plus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        String candidate;
        if (plus) {
            candidate = "+" + digits;
        } else if (digits.length() == 10) {
            candidate = "+1" + digits;
        } else if (digits.length() == 11 && digits.startsWith("1")) {
            candidate = "+" + digits;
        } else {
            return Optional.empty();
        }
        return E164.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    /** The patient's address for a channel, normalised, or empty when missing or invalid. */
    public static Optional<String> forChannel(NotificationChannel channel, Patient p) {
        return channel == NotificationChannel.EMAIL ? email(p.getEmail()) : phoneE164(p.getPhone());
    }

    /** {@code m•••@example.com}. */
    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        return email.substring(0, Math.min(1, email.length())) + "•••" + (at < 0 ? "" : email.substring(at));
    }

    /** {@code •••-•••-0100}. */
    public static String maskPhone(String e164) {
        String last4 = e164.length() >= 4 ? e164.substring(e164.length() - 4) : e164;
        return "•••-•••-" + last4;
    }

    public static String mask(NotificationChannel channel, String normalised) {
        return channel == NotificationChannel.EMAIL ? maskEmail(normalised) : maskPhone(normalised);
    }
}
