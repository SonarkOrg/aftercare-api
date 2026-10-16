package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.NotificationChannel;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Models.NotificationOutbox;
import com.aftercare.aftercareapi.Models.Patient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds the message at send time from live data. The code exists only in local variables; nothing here is logged. */
@Component
public class NotificationRenderer {

    private final String baseUrl;

    public NotificationRenderer(@Value("${aftercare.frontend-base-url:}") String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public NotificationTemplates.Rendered render(NotificationOutbox row, Patient patient, ClinicSettings clinic, String code) {
        String link = baseUrl + "/q/" + code;
        Language lang = row.getLanguage();
        if (row.getChannel() == NotificationChannel.EMAIL) {
            return NotificationTemplates.email(row.getTemplate(), lang, patient.getFirstName(), clinic.getName(),
                    clinic.getPhone(), link);
        }
        return NotificationTemplates.sms(row.getTemplate(), lang, patient.getFirstName(), clinic.getName(), link);
    }
}
