package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "clinic_settings")
public class ClinicSettings {

    public static final long ID = 1L;

    @Id
    private Long id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(length = 200)
    private String addressLine;
    @Column(length = 100)
    private String city;
    @Column(nullable = false, length = 2)
    private String province;
    @Column(length = 7)
    private String postalCode;
    @Column(length = 25)
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language defaultLanguage;
    @Column(nullable = false)
    private boolean notifyHigh;
    @Column(nullable = false)
    private boolean notifyMedium;
    private Instant updatedAt;
    @Column(length = 255)
    private String updatedBySub;
    @Column(length = 255)
    private String updatedByName;

    // ---- notifications (defaults equal the SQL defaults of V4)
    @Column(nullable = false)
    private boolean emailEnabled = false;
    @Column(nullable = false)
    private boolean smsEnabled = false;
    @Column(name = "checkin_reminder_enabled", nullable = false)
    private boolean checkInReminderEnabled = false;
    @Column(name = "checkin_reminder_time", nullable = false)
    private LocalTime checkInReminderTime = LocalTime.of(18, 0);
    @Column(nullable = false)
    private boolean appointmentReminderEnabled = false;
    @Column(nullable = false)
    private int appointmentReminderHours = 48;
    @Column(name = "missed_checkin_enabled", nullable = false)
    private boolean missedCheckInEnabled = true;
    @Column(name = "missed_checkin_cutoff", nullable = false)
    private LocalTime missedCheckInCutoff = LocalTime.of(20, 0);
    private Instant notificationsUpdatedAt;
    @Column(length = 255)
    private String notificationsUpdatedBySub;
    @Column(length = 255)
    private String notificationsUpdatedByName;

    // ---- retention (null = keep indefinitely)
    private Integer retentionClosedFollowUpMonths;
    private Integer retentionDeletedDocumentMonths;
    private Integer retentionMessageMonths;
    private Integer retentionNotificationMonths;
    private Integer retentionAuditMonths;
    private Instant retentionUpdatedAt;
    @Column(length = 255)
    private String retentionUpdatedBySub;
    @Column(length = 255)
    private String retentionUpdatedByName;
    @Version
    private Long version;
}
