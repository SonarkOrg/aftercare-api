package com.aftercare.aftercareapi.Services.notifications;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Enums.ContactConsent;
import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Models.Dto.ContactPreferencesDTO;
import com.aftercare.aftercareapi.Models.Dto.ContactPreferencesRequest;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import com.aftercare.aftercareapi.Services.Actor;
import com.aftercare.aftercareapi.Services.AuditService;
import com.aftercare.aftercareapi.Services.ClinicSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-patient channel, language and consent. Consent gates every send; changes are audited by field name only. */
@Service
public class ContactPreferencesService {

    private final PatientRepository patients;
    private final ClinicSettingsService settings;
    private final AuditService audit;
    private final Clock clock;

    public ContactPreferencesService(PatientRepository patients, ClinicSettingsService settings, AuditService audit, Clock clock) {
        this.patients = patients;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ContactPreferencesDTO get(Long patientId) {
        Patient p = patients.findById(patientId).orElseThrow(PatientNotFoundException::new);
        return toDto(p, settings.current());
    }

    @Transactional
    public ContactPreferencesDTO update(Long patientId, ContactPreferencesRequest r, Actor actor) {
        Patient p = patients.findById(patientId).orElseThrow(PatientNotFoundException::new);
        if (r.contactConsent() == ContactConsent.UNKNOWN && p.getContactConsent() != ContactConsent.UNKNOWN) {
            throw ApiException.validation("contactConsent", "cannot be reset to UNKNOWN");
        }
        List<String> changed = new ArrayList<>();
        if (p.getPreferredChannel() != r.preferredChannel()) changed.add("preferredChannel");
        if (p.getPreferredLanguage() != r.language()) changed.add("language");
        boolean consentChanged = p.getContactConsent() != r.contactConsent();
        if (consentChanged) changed.add("contactConsent");
        p.setPreferredChannel(r.preferredChannel());
        p.setPreferredLanguage(r.language());
        if (consentChanged) {
            p.setContactConsent(r.contactConsent());
            p.setContactConsentUpdatedAt(Instant.now(clock));
            p.setContactConsentUpdatedBySub(actor.sub());
            p.setContactConsentUpdatedByName(actor.name());
        }
        patients.saveAndFlush(p);
        if (!changed.isEmpty()) {
            changed.sort(null);
            audit.recordChange(actor, AuditAction.CONTACT_PREFERENCES_UPDATED, patientId, AuditResource.PATIENT, patientId,
                    String.join(",", changed));
        }
        return toDto(p, settings.current());
    }

    private static ContactPreferencesDTO toDto(Patient p, ClinicSettings cfg) {
        Language effective = p.getPreferredLanguage() != null ? p.getPreferredLanguage() : cfg.getDefaultLanguage();
        boolean email = cfg.isEmailEnabled() && Recipients.email(p.getEmail()).isPresent();
        boolean sms = cfg.isSmsEnabled() && Recipients.phoneE164(p.getPhone()).isPresent();
        return new ContactPreferencesDTO(p.getId(), p.getPreferredChannel(), p.getPreferredLanguage(), effective,
                p.getContactConsent(), p.getContactConsentUpdatedAt(), p.getContactConsentUpdatedByName(), email, sms);
    }
}
