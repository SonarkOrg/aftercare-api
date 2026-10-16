package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Enums.EmailProvider;
import com.aftercare.aftercareapi.Enums.SmsProvider;
import com.aftercare.aftercareapi.Models.Dto.NotificationSettingsDTO;
import com.aftercare.aftercareapi.Models.Dto.NotificationSettingsRequest;
import com.aftercare.aftercareapi.Models.Dto.RetentionSettingsDTO;
import com.aftercare.aftercareapi.Models.Dto.RetentionSettingsRequest;
import com.aftercare.aftercareapi.config.NotificationProperties;
import com.aftercare.aftercareapi.config.RetentionProperties;
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
import java.time.LocalTime;
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
    private final NotificationProperties notificationProps;
    private final RetentionProperties retentionProps;
    private final TransactionTemplate insertTx;

    public ClinicSettingsService(ClinicSettingsRepository repository, AuditService audit, Clock clock,
                                 PlatformTransactionManager txManager, NotificationProperties notificationProps,
                                 RetentionProperties retentionProps) {
        this.notificationProps = notificationProps;
        this.retentionProps = retentionProps;
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

    // ---- notification settings (ADMIN)

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    @Transactional
    public NotificationSettingsDTO getNotifications() {
        return notificationsDto(load());
    }

    @Transactional
    public NotificationSettingsDTO updateNotifications(NotificationSettingsRequest r, Actor actor) {
        ClinicSettings s = load();
        Fields f = new Fields();
        LocalTime reminder = time(f, "checkInReminderTime", r.checkInReminderTime());
        LocalTime cutoff = time(f, "missedCheckInCutoff", r.missedCheckInCutoff());
        if (reminder != null && cutoff != null && !reminder.isBefore(cutoff)) {
            f.error("checkInReminderTime", "must be before the missed check-in cutoff");
        }
        int hours = r.appointmentReminderHours() == null ? 0 : r.appointmentReminderHours();
        if (hours < 24 || hours > 72) {
            f.error("appointmentReminderHours", "must be between 24 and 72");
        }
        f.check();
        List<String> changed = new ArrayList<>();
        if (s.isEmailEnabled() != r.emailEnabled()) changed.add("emailEnabled");
        if (s.isSmsEnabled() != r.smsEnabled()) changed.add("smsEnabled");
        if (s.isCheckInReminderEnabled() != r.checkInReminderEnabled()) changed.add("checkInReminderEnabled");
        if (!s.getCheckInReminderTime().equals(reminder)) changed.add("checkInReminderTime");
        if (s.isAppointmentReminderEnabled() != r.appointmentReminderEnabled()) changed.add("appointmentReminderEnabled");
        if (s.getAppointmentReminderHours() != hours) changed.add("appointmentReminderHours");
        if (s.isMissedCheckInEnabled() != r.missedCheckInEnabled()) changed.add("missedCheckInEnabled");
        if (!s.getMissedCheckInCutoff().equals(cutoff)) changed.add("missedCheckInCutoff");
        s.setEmailEnabled(r.emailEnabled());
        s.setSmsEnabled(r.smsEnabled());
        s.setCheckInReminderEnabled(r.checkInReminderEnabled());
        s.setCheckInReminderTime(reminder);
        s.setAppointmentReminderEnabled(r.appointmentReminderEnabled());
        s.setAppointmentReminderHours(hours);
        s.setMissedCheckInEnabled(r.missedCheckInEnabled());
        s.setMissedCheckInCutoff(cutoff);
        s.setNotificationsUpdatedAt(Instant.now(clock));
        s.setNotificationsUpdatedBySub(actor.sub());
        s.setNotificationsUpdatedByName(actor.name());
        s = repository.saveAndFlush(s);
        audit.recordChange(actor, AuditAction.NOTIFICATION_SETTINGS_UPDATED, null, AuditResource.CLINIC_SETTINGS,
                ClinicSettings.ID, changed.isEmpty() ? null : String.join(",", changed));
        return notificationsDto(s);
    }

    private static LocalTime time(Fields f, String field, String value) {
        if (value == null || !TIME.matcher(value.trim()).matches()) {
            f.error(field, "must be a time as HH:mm");
            return null;
        }
        return LocalTime.parse(value.trim());
    }

    private NotificationSettingsDTO notificationsDto(ClinicSettings s) {
        EmailProvider email = "smtp".equalsIgnoreCase(notificationProps.email().provider()) ? EmailProvider.SMTP : EmailProvider.LOG;
        SmsProvider sms = "twilio".equalsIgnoreCase(notificationProps.sms().provider()) ? SmsProvider.TWILIO : SmsProvider.LOG;
        return new NotificationSettingsDTO(s.isEmailEnabled(), s.isSmsEnabled(), email, sms, s.isCheckInReminderEnabled(),
                s.getCheckInReminderTime(), s.isAppointmentReminderEnabled(), s.getAppointmentReminderHours(),
                s.isMissedCheckInEnabled(), s.getMissedCheckInCutoff(), s.getNotificationsUpdatedAt(),
                s.getNotificationsUpdatedByName());
    }

    // ---- retention settings (ADMIN)

    @Transactional
    public RetentionSettingsDTO getRetention() {
        return retentionDto(load());
    }

    @Transactional
    public RetentionSettingsDTO updateRetention(RetentionSettingsRequest r, Actor actor) {
        ClinicSettings s = load();
        Fields f = new Fields();
        months(f, "closedFollowUpMonths", r.closedFollowUpMonths());
        months(f, "deletedDocumentMonths", r.deletedDocumentMonths());
        months(f, "messageMonths", r.messageMonths());
        months(f, "notificationMonths", r.notificationMonths());
        months(f, "auditMonths", r.auditMonths());
        Integer am = r.auditMonths();
        if (am != null && am >= 1 && am < retentionProps.auditMinMonths()) {
            f.error("auditMonths", "must be at least " + retentionProps.auditMinMonths() + " months");
        }
        f.check();
        List<String> changed = new ArrayList<>();
        diff(changed, "closedFollowUpMonths", s.getRetentionClosedFollowUpMonths(), r.closedFollowUpMonths());
        diff(changed, "deletedDocumentMonths", s.getRetentionDeletedDocumentMonths(), r.deletedDocumentMonths());
        diff(changed, "messageMonths", s.getRetentionMessageMonths(), r.messageMonths());
        diff(changed, "notificationMonths", s.getRetentionNotificationMonths(), r.notificationMonths());
        diff(changed, "auditMonths", s.getRetentionAuditMonths(), r.auditMonths());
        s.setRetentionClosedFollowUpMonths(r.closedFollowUpMonths());
        s.setRetentionDeletedDocumentMonths(r.deletedDocumentMonths());
        s.setRetentionMessageMonths(r.messageMonths());
        s.setRetentionNotificationMonths(r.notificationMonths());
        s.setRetentionAuditMonths(r.auditMonths());
        s.setRetentionUpdatedAt(Instant.now(clock));
        s.setRetentionUpdatedBySub(actor.sub());
        s.setRetentionUpdatedByName(actor.name());
        s = repository.saveAndFlush(s);
        audit.recordChange(actor, AuditAction.RETENTION_SETTINGS_UPDATED, null, AuditResource.RETENTION, null,
                changed.isEmpty() ? null : String.join(",", changed));
        return retentionDto(s);
    }

    private static void months(Fields f, String field, Integer v) {
        if (v != null && (v < 1 || v > 1200)) {
            f.error(field, "must be between 1 and 1200 months");
        }
    }

    private static void diff(List<String> changed, String field, Integer old, Integer now) {
        if (!Objects.equals(old, now)) {
            changed.add(field + "=" + old + "->" + now);
        }
    }

    private RetentionSettingsDTO retentionDto(ClinicSettings s) {
        RetentionProperties.Purge p = retentionProps.purge();
        return new RetentionSettingsDTO(s.getRetentionClosedFollowUpMonths(), s.getRetentionDeletedDocumentMonths(),
                s.getRetentionMessageMonths(), s.getRetentionNotificationMonths(), s.getRetentionAuditMonths(),
                p.enabled(), p.dryRun(), s.getRetentionUpdatedAt(), s.getRetentionUpdatedByName());
    }

    /** The settings row itself (created with defaults on first use), for jobs and renderers. */
    @Transactional
    public ClinicSettings current() {
        return load();
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
