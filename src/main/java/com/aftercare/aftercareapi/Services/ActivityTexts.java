package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.*;

/** French (fr-CA) twins of the timeline summaries. Vocabulary follows the i18n glossary. User content is never translated. */
public final class ActivityTexts {

    private ActivityTexts() {
    }

    public static String noteAdded() {
        return "Note d'évolution";
    }

    public static String checkInSubmitted(Object date) {
        return "Suivi quotidien reçu pour le " + date;
    }

    public static String alertCreated(AlertSeverity severity, String messageFr) {
        return "Alerte " + (severity == AlertSeverity.HIGH ? "élevée" : "moyenne") + " : " + messageFr;
    }

    public static String alertAcknowledged(String messageFr) {
        return "Alerte prise en charge : " + messageFr;
    }

    public static String alertResolved(String messageFr) {
        return "Alerte résolue : " + messageFr;
    }

    public static String appointmentCreated(AppointmentType type, String dateTime) {
        return type(type) + " prévue le " + dateTime;
    }

    public static String appointmentRescheduled(AppointmentType type, String dateTime) {
        return type(type) + " déplacée au " + dateTime;
    }

    public static String appointmentDeleted(AppointmentType type, String date) {
        return type(type) + " du " + date + " supprimée";
    }

    public static String appointmentStatusChanged(AppointmentType type, String date, AppointmentStatus old, AppointmentStatus now) {
        return type(type) + " du " + date + " : " + status(old) + " → " + status(now);
    }

    public static String intakeSubmitted(int flags) {
        return "Questionnaire préalable reçu (" + flags + (flags <= 1 ? " signalement)" : " signalements)");
    }

    public static String followUpStarted(Specialty specialty, int days) {
        return "Suivi postopératoire commencé : " + specialty(specialty) + " (" + days + " jours)";
    }

    public static String followUpClosed() {
        return "Suivi postopératoire terminé";
    }

    public static String documentUploaded(String title, DocumentCategory category) {
        return "Document téléversé : " + title + " (" + category(category).toLowerCase(java.util.Locale.ROOT) + ")";
    }

    public static String documentDeleted(String title) {
        return "Document supprimé : " + title;
    }

    public static String type(AppointmentType t) {
        return switch (t) {
            case PRE_OP_CONSULT -> "Consultation préopératoire";
            case SURGERY -> "Chirurgie";
            case POST_OP_FOLLOWUP -> "Visite de suivi postopératoire";
            case GENERAL_CONSULT -> "Consultation générale";
        };
    }

    public static String status(AppointmentStatus s) {
        return switch (s) {
            case SCHEDULED -> "Prévu";
            case READY -> "Prêt";
            case NEEDS_REVIEW -> "À examiner";
            case CANCELLED -> "Annulé";
            case COMPLETED -> "Terminé";
            case NO_SHOW -> "Absence";
        };
    }

    public static String specialty(Specialty s) {
        return switch (s) {
            case VISCERAL_SURGERY -> "chirurgie viscérale";
            case ORTHOPEDIC_SURGERY -> "chirurgie orthopédique";
        };
    }

    public static String category(DocumentCategory c) {
        return switch (c) {
            case DISCHARGE_SUMMARY -> "Résumé de congé";
            case CONSENT -> "Consentement";
            case PRESCRIPTION -> "Ordonnance";
            case LAB_RESULT -> "Résultat de laboratoire";
            case IMAGING -> "Imagerie";
            case REFERRAL -> "Demande de consultation";
            case OTHER -> "Autre";
        };
    }
}
