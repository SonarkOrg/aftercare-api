package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.NotificationTemplate;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.aftercare.aftercareapi.Enums.NotificationTemplate.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationTemplatesTests {

    private static final String LINK = "https://app.test/q/CODE";

    private static String sms(NotificationTemplate t, Language l) {
        return NotificationTemplates.sms(t, l, "Marie", "Clinique Saint-Laurent", LINK).body();
    }

    @Test
    void smsTextsAreExact() {
        assertEquals("Clinique Saint-Laurent\u00a0: Bonjour Marie, voici votre lien personnel pour le suivi après votre intervention\u00a0: " + LINK, sms(FOLLOW_UP_LINK, Language.FR));
        assertEquals("Clinique Saint-Laurent: Hi Marie, here is your personal aftercare check-in link: " + LINK, sms(FOLLOW_UP_LINK, Language.EN));
        assertEquals("Clinique Saint-Laurent\u00a0: Bonjour Marie, n'oubliez pas de remplir votre suivi quotidien d'aujourd'hui\u00a0: " + LINK, sms(CHECKIN_REMINDER, Language.FR));
        assertEquals("Clinique Saint-Laurent: Hi Marie, please complete today's check-in: " + LINK, sms(CHECKIN_REMINDER, Language.EN));
        assertEquals("Clinique Saint-Laurent\u00a0: Bonjour Marie, merci de remplir votre questionnaire préalable à votre rendez-vous\u00a0: " + LINK, sms(APPOINTMENT_LINK, Language.FR));
        assertEquals("Clinique Saint-Laurent: Hi Marie, please complete your questionnaire before your appointment: " + LINK, sms(APPOINTMENT_LINK, Language.EN));
        assertEquals("Clinique Saint-Laurent\u00a0: Bonjour Marie, rappel\u00a0: votre questionnaire préalable au rendez-vous n'est pas rempli\u00a0: " + LINK, sms(APPOINTMENT_REMINDER, Language.FR));
        assertEquals("Clinique Saint-Laurent: Reminder - Hi Marie, your pre-appointment questionnaire is not complete yet: " + LINK, sms(APPOINTMENT_REMINDER, Language.EN));
        assertNull(NotificationTemplates.sms(FOLLOW_UP_LINK, Language.FR, "Marie", "C", LINK).subject());
    }

    private static NotificationTemplates.Rendered email(NotificationTemplate t, Language l, String phone) {
        return NotificationTemplates.email(t, l, "Marie", "Clinique Saint-Laurent", phone, LINK);
    }

    @Test
    void emailSubjectsAreExact() {
        assertEquals("Clinique Saint-Laurent \u2013 Votre suivi après l'intervention", email(FOLLOW_UP_LINK, Language.FR, null).subject());
        assertEquals("Clinique Saint-Laurent - Your aftercare check-in", email(FOLLOW_UP_LINK, Language.EN, null).subject());
        assertEquals("Clinique Saint-Laurent \u2013 Rappel\u00a0: suivi quotidien", email(CHECKIN_REMINDER, Language.FR, null).subject());
        assertEquals("Clinique Saint-Laurent - Reminder: today's check-in", email(CHECKIN_REMINDER, Language.EN, null).subject());
        assertEquals("Clinique Saint-Laurent \u2013 Questionnaire préalable à votre rendez-vous", email(APPOINTMENT_LINK, Language.FR, null).subject());
        assertEquals("Clinique Saint-Laurent - Questionnaire before your appointment", email(APPOINTMENT_LINK, Language.EN, null).subject());
        assertEquals("Clinique Saint-Laurent \u2013 Rappel\u00a0: questionnaire préalable à votre rendez-vous", email(APPOINTMENT_REMINDER, Language.FR, null).subject());
        assertEquals("Clinique Saint-Laurent - Reminder: pre-appointment questionnaire", email(APPOINTMENT_REMINDER, Language.EN, null).subject());
    }

    @Test
    void emailBodyIsTheSmsLineThenTheFooter() {
        String fr = email(FOLLOW_UP_LINK, Language.FR, "514-555-0199").body();
        assertEquals(sms(FOLLOW_UP_LINK, Language.FR) + "\n\n"
                + "Ce lien est personnel\u00a0: ne le partagez pas. Ce message est envoyé automatiquement. N'y répondez pas.\n"
                + "En cas d'urgence, composez le 911. Pour un conseil santé, appelez Info-Santé au 811.\n"
                + "Pour ne plus recevoir ces messages, communiquez avec la clinique au 514-555-0199.", fr);
        String en = email(CHECKIN_REMINDER, Language.EN, "514-555-0199").body();
        assertEquals(sms(CHECKIN_REMINDER, Language.EN) + "\n\n"
                + "This link is personal: do not share it. This message is sent automatically; do not reply.\n"
                + "In an emergency, call 911. For health advice, call Info-Santé at 811.\n"
                + "To stop receiving these messages, contact the clinic at 514-555-0199.", en);
    }

    @Test
    void theFooterWithoutAClinicPhone() {
        assertTrue(email(FOLLOW_UP_LINK, Language.FR, null).body().endsWith("communiquez avec la clinique."));
        assertTrue(email(FOLLOW_UP_LINK, Language.FR, "  ").body().endsWith("communiquez avec la clinique."));
        assertTrue(email(FOLLOW_UP_LINK, Language.EN, null).body().endsWith("contact the clinic."));
    }

    @Test
    void onlyTheWhitelistedPlaceholdersAreUsed() {
        Set<String> used = new HashSet<>();
        for (String raw : NotificationTemplates.allRawTemplates()) {
            used.addAll(NotificationTemplates.placeholdersOf(raw));
        }
        assertEquals(Set.of("firstName", "clinicName", "link", "clinicPhoneSentenceFr", "clinicPhoneSentenceEn"), used);
        assertEquals(NotificationTemplates.ALLOWED_PLACEHOLDERS, used);
        // rendering leaves no placeholder behind and never mentions clinical words
        for (NotificationTemplate t : NotificationTemplate.values()) {
            for (Language l : Language.values()) {
                String all = email(t, l, "514-555-0199").subject() + email(t, l, "514-555-0199").body() + sms(t, l);
                assertFalse(all.contains("{"), all);
                for (String banned : BANNED_WORDS) {
                    assertFalse(all.toLowerCase().contains(banned), t + " " + l + " mentions " + banned);
                }
            }
        }
    }

    private static final java.util.List<String> BANNED_WORDS = java.util.List.of("surgery", "chirurgie", "orthop", "visceral",
            "diagnos", "ramq", "genou", "knee", "consult", "rendez-vous le", "appointment on");

    @Test
    void worstCaseSmsStaysUnder320Characters() {
        String name = "N".repeat(60); // cut to 40
        String clinic = "C".repeat(120);
        String link = "https://app.example.ca/q/" + "x".repeat(43);
        for (NotificationTemplate t : NotificationTemplate.values()) {
            for (Language l : Language.values()) {
                String body = NotificationTemplates.sms(t, l, name, clinic, link).body();
                assertTrue(body.length() <= 320, t + " " + l + " " + body.length());
            }
        }
    }

    @Test
    void firstNameIsTrimmedCleanedAndCut() {
        assertEquals("Marie", NotificationTemplates.sanitizeFirstName("  Marie \n"));
        assertEquals("MarieClaire", NotificationTemplates.sanitizeFirstName("Marie\u0000\u0007\r\n\tClaire"));
        assertEquals(40, NotificationTemplates.sanitizeFirstName("A".repeat(100)).length());
        assertEquals("", NotificationTemplates.sanitizeFirstName(null));
        String body = NotificationTemplates.sms(FOLLOW_UP_LINK, Language.EN, "Eve\nEvil", "Clinic", LINK).body();
        assertFalse(body.contains("\n"));
        assertTrue(body.contains("Hi EveEvil,"));
    }

    @Test
    void aFirstNameLookingLikeAPlaceholderIsNotExpanded() {
        String body = NotificationTemplates.sms(FOLLOW_UP_LINK, Language.EN, "{link}", "Clinic", LINK).body();
        assertTrue(body.contains("Hi {link},"), body);
    }
}
