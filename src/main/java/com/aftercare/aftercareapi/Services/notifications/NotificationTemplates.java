package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.NotificationTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The four patient messages in French and English. No PHI: only the first name, the clinic name and phone, and the
 * personal link may ever appear. Never a specialty, diagnosis, appointment type or date, answers or health numbers.
 */
public final class NotificationTemplates {

    /** Everything a template may substitute. {@code clinicPhoneSentence*} derive from the clinic phone only. */
    public static final Set<String> ALLOWED_PLACEHOLDERS =
            Set.of("firstName", "clinicName", "link", "clinicPhoneSentenceFr", "clinicPhoneSentenceEn");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final int MAX_FIRST_NAME = 40;

    private static final Map<NotificationTemplate, String> SMS_FR = new EnumMap<>(NotificationTemplate.class);
    private static final Map<NotificationTemplate, String> SMS_EN = new EnumMap<>(NotificationTemplate.class);
    private static final Map<NotificationTemplate, String> SUBJECT_FR = new EnumMap<>(NotificationTemplate.class);
    private static final Map<NotificationTemplate, String> SUBJECT_EN = new EnumMap<>(NotificationTemplate.class);

    private static final String FOOTER_FR =
            "Ce lien est personnel\u00a0: ne le partagez pas. Ce message est envoyé automatiquement. N'y répondez pas.\n"
                    + "En cas d'urgence, composez le 911. Pour un conseil santé, appelez Info-Santé au 811.\n"
                    + "Pour ne plus recevoir ces messages, communiquez avec la clinique{clinicPhoneSentenceFr}.";
    private static final String FOOTER_EN =
            "This link is personal: do not share it. This message is sent automatically; do not reply.\n"
                    + "In an emergency, call 911. For health advice, call Info-Santé at 811.\n"
                    + "To stop receiving these messages, contact the clinic{clinicPhoneSentenceEn}.";

    static {
        SMS_FR.put(NotificationTemplate.FOLLOW_UP_LINK,
                "{clinicName}\u00a0: Bonjour {firstName}, voici votre lien personnel pour le suivi après votre intervention\u00a0: {link}");
        SMS_EN.put(NotificationTemplate.FOLLOW_UP_LINK,
                "{clinicName}: Hi {firstName}, here is your personal aftercare check-in link: {link}");
        SMS_FR.put(NotificationTemplate.CHECKIN_REMINDER,
                "{clinicName}\u00a0: Bonjour {firstName}, n'oubliez pas de remplir votre suivi quotidien d'aujourd'hui\u00a0: {link}");
        SMS_EN.put(NotificationTemplate.CHECKIN_REMINDER,
                "{clinicName}: Hi {firstName}, please complete today's check-in: {link}");
        SMS_FR.put(NotificationTemplate.APPOINTMENT_LINK,
                "{clinicName}\u00a0: Bonjour {firstName}, merci de remplir votre questionnaire préalable à votre rendez-vous\u00a0: {link}");
        SMS_EN.put(NotificationTemplate.APPOINTMENT_LINK,
                "{clinicName}: Hi {firstName}, please complete your questionnaire before your appointment: {link}");
        SMS_FR.put(NotificationTemplate.APPOINTMENT_REMINDER,
                "{clinicName}\u00a0: Bonjour {firstName}, rappel\u00a0: votre questionnaire préalable au rendez-vous n'est pas rempli\u00a0: {link}");
        SMS_EN.put(NotificationTemplate.APPOINTMENT_REMINDER,
                "{clinicName}: Reminder - Hi {firstName}, your pre-appointment questionnaire is not complete yet: {link}");

        SUBJECT_FR.put(NotificationTemplate.FOLLOW_UP_LINK, "{clinicName} \u2013 Votre suivi après l'intervention");
        SUBJECT_EN.put(NotificationTemplate.FOLLOW_UP_LINK, "{clinicName} - Your aftercare check-in");
        SUBJECT_FR.put(NotificationTemplate.CHECKIN_REMINDER, "{clinicName} \u2013 Rappel\u00a0: suivi quotidien");
        SUBJECT_EN.put(NotificationTemplate.CHECKIN_REMINDER, "{clinicName} - Reminder: today's check-in");
        SUBJECT_FR.put(NotificationTemplate.APPOINTMENT_LINK, "{clinicName} \u2013 Questionnaire préalable à votre rendez-vous");
        SUBJECT_EN.put(NotificationTemplate.APPOINTMENT_LINK, "{clinicName} - Questionnaire before your appointment");
        SUBJECT_FR.put(NotificationTemplate.APPOINTMENT_REMINDER, "{clinicName} \u2013 Rappel\u00a0: questionnaire préalable à votre rendez-vous");
        SUBJECT_EN.put(NotificationTemplate.APPOINTMENT_REMINDER, "{clinicName} - Reminder: pre-appointment questionnaire");
    }

    private NotificationTemplates() {
    }

    /** Subject and body of an email, or just the body of an SMS (subject null). */
    public record Rendered(String subject, String body) {
    }

    public static Rendered sms(NotificationTemplate t, Language lang, String firstName, String clinicName, String link) {
        return new Rendered(null, fill(smsRaw(t, lang), values(firstName, clinicName, null, link)));
    }

    public static Rendered email(NotificationTemplate t, Language lang, String firstName, String clinicName,
                                 String clinicPhone, String link) {
        Function<String, String> v = values(firstName, clinicName, clinicPhone, link);
        String subject = fill(lang == Language.EN ? SUBJECT_EN.get(t) : SUBJECT_FR.get(t), v);
        String body = fill(smsRaw(t, lang), v) + "\n\n" + fill(footerRaw(lang), v);
        return new Rendered(subject, body);
    }

    /** First name as it may appear in a message: trimmed, control characters removed, at most 40 characters. */
    public static String sanitizeFirstName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        raw.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(sb::appendCodePoint);
        String s = sb.toString().trim();
        if (s.codePointCount(0, s.length()) > MAX_FIRST_NAME) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_FIRST_NAME)).trim();
        }
        return s;
    }

    /** Every raw template string, for the no-PHI whitelist test. */
    public static List<String> allRawTemplates() {
        List<String> all = new ArrayList<>();
        for (NotificationTemplate t : NotificationTemplate.values()) {
            all.add(SMS_FR.get(t));
            all.add(SMS_EN.get(t));
            all.add(SUBJECT_FR.get(t));
            all.add(SUBJECT_EN.get(t));
        }
        all.add(FOOTER_FR);
        all.add(FOOTER_EN);
        return all;
    }

    /** The placeholder names used in a raw template string. */
    public static Set<String> placeholdersOf(String raw) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(raw);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String smsRaw(NotificationTemplate t, Language lang) {
        return lang == Language.EN ? SMS_EN.get(t) : SMS_FR.get(t);
    }

    private static String footerRaw(Language lang) {
        return lang == Language.EN ? FOOTER_EN : FOOTER_FR;
    }

    private static Function<String, String> values(String firstName, String clinicName, String clinicPhone, String link) {
        boolean phone = clinicPhone != null && !clinicPhone.isBlank();
        return name -> switch (name) {
            case "firstName" -> sanitizeFirstName(firstName);
            case "clinicName" -> clinicName == null ? "" : clinicName;
            case "link" -> link;
            case "clinicPhoneSentenceFr" -> phone ? " au " + clinicPhone.trim() : "";
            case "clinicPhoneSentenceEn" -> phone ? " at " + clinicPhone.trim() : "";
            default -> throw new IllegalStateException("Unknown template placeholder");
        };
    }

    /** Single pass, so a substituted value is never scanned for placeholders again. */
    private static String fill(String raw, Function<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(values.apply(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
