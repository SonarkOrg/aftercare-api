package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Models.ClinicSettings;
import com.aftercare.aftercareapi.Models.Dto.ClinicSettingsDTO;
import com.aftercare.aftercareapi.Models.Dto.ClinicSettingsRequest;
import com.aftercare.aftercareapi.Repositories.ClinicSettingsRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Clinic profile and clinic-wide notification preferences (a single row, id 1). */
@Service
public class ClinicSettingsService {

    private static final Pattern PROVINCE = Pattern.compile("^[A-Z]{2}$");

    private final ClinicSettingsRepository repository;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate insertTx;

    public ClinicSettingsService(ClinicSettingsRepository repository, AuditService audit, Clock clock,
                                 PlatformTransactionManager txManager) {
        this.insertTx = new TransactionTemplate(txManager);
        this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ClinicSettingsDTO get() {
        return toDto(load());
    }

    @Transactional
    public ClinicSettingsDTO update(ClinicSettingsRequest r, Actor actor) {
        ClinicSettings s = load();
        Fields f = new Fields();
        String name = f.required("name", r.name(), 120);
        String address = f.optional("addressLine", r.addressLine(), 200);
        String city = f.optional("city", r.city(), 100);
        String province = r.province() == null ? "" : r.province().trim();
        if (!PROVINCE.matcher(province).matches()) {
            f.error("province", "must be a two-letter uppercase code");
        }
        String postal = f.postal("postalCode", r.postalCode());
        String phone = f.phone("phone", r.phone(), false);
        f.check();
        boolean high = r.notifySeverities().contains(AlertSeverity.HIGH);
        boolean medium = r.notifySeverities().contains(AlertSeverity.MEDIUM);

        List<String> changed = new ArrayList<>();
        if (!Objects.equals(s.getName(), name)) changed.add("name");
        if (!Objects.equals(s.getAddressLine(), address)) changed.add("addressLine");
        if (!Objects.equals(s.getCity(), city)) changed.add("city");
        if (!Objects.equals(s.getProvince(), province)) changed.add("province");
        if (!Objects.equals(s.getPostalCode(), postal)) changed.add("postalCode");
        if (!Objects.equals(s.getPhone(), phone)) changed.add("phone");
        if (s.getDefaultLanguage() != r.defaultLanguage()) changed.add("defaultLanguage");
        if (s.isNotifyHigh() != high || s.isNotifyMedium() != medium) changed.add("notifySeverities");

        s.setName(name);
        s.setAddressLine(address);
        s.setCity(city);
        s.setProvince(province);
        s.setPostalCode(postal);
        s.setPhone(phone);
        s.setDefaultLanguage(r.defaultLanguage());
        s.setNotifyHigh(high);
        s.setNotifyMedium(medium);
        s.setUpdatedAt(Instant.now(clock));
        s.setUpdatedBySub(actor.sub());
        s.setUpdatedByName(actor.name());
        s = repository.saveAndFlush(s);
        audit.recordChange(actor, AuditAction.CLINIC_SETTINGS_UPDATED, null, AuditResource.CLINIC_SETTINGS, ClinicSettings.ID,
                changed.isEmpty() ? null : String.join(",", changed));
        return toDto(s);
    }

    @Transactional
    public String clinicName() {
        return load().getName();
    }

    @Transactional
    public String clinicPhone() {
        return load().getPhone();
    }

    private ClinicSettings load() {
        return repository.findById(ClinicSettings.ID).orElseGet(() -> {
            ClinicSettings d = new ClinicSettings();
            d.setId(ClinicSettings.ID);
            d.setName("Aftercare clinic");
            d.setProvince("QC");
            d.setDefaultLanguage(Language.FR);
            d.setNotifyHigh(true);
            d.setNotifyMedium(true);
            try {
                // Own transaction: a unique-key failure would otherwise poison the caller's transaction.
                insertTx.executeWithoutResult(status -> repository.saveAndFlush(d));
            } catch (DataIntegrityViolationException raced) {
                // Another request created the singleton row first.
            }
            return repository.findById(ClinicSettings.ID).orElseThrow(() -> raced());
        });
    }

    private static IllegalStateException raced() {
        return new IllegalStateException("Clinic settings row missing after creation");
    }

    static ClinicSettingsDTO toDto(ClinicSettings s) {
        List<AlertSeverity> sev = new ArrayList<>();
        if (s.isNotifyHigh()) sev.add(AlertSeverity.HIGH);
        if (s.isNotifyMedium()) sev.add(AlertSeverity.MEDIUM);
        return new ClinicSettingsDTO(s.getName(), s.getAddressLine(), s.getCity(), s.getProvince(), s.getPostalCode(),
                s.getPhone(), s.getDefaultLanguage(), sev, s.getUpdatedAt(), s.getUpdatedByName());
    }
}
