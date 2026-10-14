package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireRegistry;
import com.aftercare.aftercareapi.Repositories.*;
import com.aftercare.aftercareapi.Services.AccessLinkService;
import com.aftercare.aftercareapi.Services.AlertService;
import com.aftercare.aftercareapi.Services.AppointmentService;
import com.aftercare.aftercareapi.Services.CheckInService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Seeds demo data in profile local, only when there are no patients. Uses the real submission pipeline. */
@Component
@Profile("local")
@Order(2)
public class LocalDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDataSeeder.class);
    static final String ACTOR = "system:seed";
    public static final String MARIE_CODE = "dev-marie-checkin-code-00000000000000000000";
    public static final String JEAN_CODE = "dev-jean-checkin-code-000000000000000000000";
    public static final String JEAN_INTAKE_CODE = "dev-jean-intake-code-0000000000000000000000";

    private final PatientRepository patients;
    private final FollowUpRepository followUps;
    private final AppointmentRepository appointments;
    private final AlertRepository alerts;
    private final QuestionnaireRegistry registry;
    private final AccessLinkService links;
    private final CheckInService checkIns;
    private final AppointmentService appointmentService;
    private final AlertService alertService;
    private final Clock clock;
    private final String frontendBaseUrl;

    public LocalDataSeeder(PatientRepository patients, FollowUpRepository followUps, AppointmentRepository appointments,
                           AlertRepository alerts, QuestionnaireRegistry registry, AccessLinkService links,
                           CheckInService checkIns, AppointmentService appointmentService, AlertService alertService,
                           Clock clock, @Value("${aftercare.frontend-base-url:http://localhost:4200}") String frontendBaseUrl) {
        this.patients = patients;
        this.followUps = followUps;
        this.appointments = appointments;
        this.alerts = alerts;
        this.registry = registry;
        this.links = links;
        this.checkIns = checkIns;
        this.appointmentService = appointmentService;
        this.alertService = alertService;
        this.clock = clock;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (patients.count() > 0) {
            return;
        }
        LocalDate t = LocalDate.now(clock);
        Patient marie = patients.save(patient("Marie", "Tremblay", "female", "marie.tremblay@example.com", "514-555-0101", "Montréal", "H2X 1Y4"));
        Patient jean = patients.save(patient("Jean", "Gagnon", "male", "jean.gagnon@example.com", "418-555-0102", "Québec", "G1R 2B5"));
        Patient sophie = patients.save(patient("Sophie", "Roy", "female", "sophie.roy@example.com", "450-555-0103", "Laval", "H7N 1A1"));

        FollowUp fMarie = followUp(marie, Specialty.VISCERAL_SURGERY, t.minusDays(3), 7, MARIE_CODE);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("PAIN", 3); ok.put("TEMPERATURE", 37.0); ok.put("WOUND_REDNESS", false); ok.put("WOUND_DISCHARGE", false);
        ok.put("NAUSEA_VOMITING", false); ok.put("ABLE_TO_EAT", true); ok.put("SHORTNESS_OF_BREATH", false);
        checkIns.recordForSeed(fMarie, t.minusDays(2), at(t.minusDays(2), 9), ok);
        Map<String, Object> bad = new LinkedHashMap<>(ok);
        bad.put("PAIN", 8); bad.put("TEMPERATURE", 38.4); bad.put("WOUND_REDNESS", true);
        CheckIn c2 = checkIns.recordForSeed(fMarie, t.minusDays(1), at(t.minusDays(1), 9), bad);
        for (Alert a : alerts.findByCheckInId(c2.getId())) {
            if (a.getLinkId().equals("WOUND_REDNESS")) {
                alertService.acknowledgeForSeed(a, at(t.minusDays(1), 10), "dev|nurse-1");
            }
        }

        FollowUp fJean = followUp(jean, Specialty.ORTHOPEDIC_SURGERY, t.minusDays(1), 14, JEAN_CODE);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("PAIN", 4); j.put("WOUND_REDNESS", false); j.put("WOUND_DISCHARGE", false); j.put("SWELLING", false);
        j.put("CALF_PAIN", true); j.put("NUMBNESS", false); j.put("MOBILITY", "walking-aid"); j.put("SHORTNESS_OF_BREATH", false);
        checkIns.recordForSeed(fJean, t.minusDays(1), at(t.minusDays(1), 9), j);

        appt(sophie, AppointmentType.PRE_OP_CONSULT, Specialty.ORTHOPEDIC_SURGERY, t, 8, 30, AppointmentStatus.COMPLETED,
                benignConsult("Douleur au genou", false), null);
        appt(jean, AppointmentType.GENERAL_CONSULT, Specialty.ORTHOPEDIC_SURGERY, t, 11, 0, AppointmentStatus.CANCELLED, null, null);
        appt(marie, AppointmentType.POST_OP_FOLLOWUP, Specialty.VISCERAL_SURGERY, t, 13, 0, AppointmentStatus.NO_SHOW, null, null);
        appt(jean, AppointmentType.POST_OP_FOLLOWUP, Specialty.ORTHOPEDIC_SURGERY, t.plusDays(1), 15, 0, AppointmentStatus.SCHEDULED, null, JEAN_INTAKE_CODE);
        Map<String, Object> surgery = new LinkedHashMap<>();
        surgery.put("WILL_ATTEND", true); surgery.put("TAKES_ANTICOAGULANT", false); surgery.put("RECENT_ILLNESS", false);
        surgery.put("FASTING_ACKNOWLEDGED", true); surgery.put("RIDE_HOME", true); surgery.put("CONSENT_READ", true);
        surgery.put("NEEDS_INTERPRETER", false);
        appt(sophie, AppointmentType.SURGERY, Specialty.ORTHOPEDIC_SURGERY, t.plusDays(1), 8, 0, AppointmentStatus.READY, surgery, null);
        Map<String, Object> flagged = benignConsult("Suivi après chirurgie", true);
        flagged.put("TAKES_ANTICOAGULANT", true);
        appt(marie, AppointmentType.PRE_OP_CONSULT, Specialty.VISCERAL_SURGERY, t.plusDays(1), 10, 30, AppointmentStatus.NEEDS_REVIEW, flagged, null);
        appt(sophie, AppointmentType.SURGERY, Specialty.ORTHOPEDIC_SURGERY, t.plusDays(5), 8, 0, AppointmentStatus.SCHEDULED, null, null);

        log.info("Seeded local data. Patient links: {}/q/{}  {}/q/{}  {}/q/{}", frontendBaseUrl, MARIE_CODE,
                frontendBaseUrl, JEAN_CODE, frontendBaseUrl, JEAN_INTAKE_CODE);
    }

    private Map<String, Object> benignConsult(String reason, boolean interpreter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("WILL_ATTEND", true); m.put("REASON_FOR_VISIT", reason); m.put("TAKES_ANTICOAGULANT", false);
        m.put("NEEDS_INTERPRETER", interpreter);
        return m;
    }

    private Patient patient(String first, String last, String gender, String email, String phone, String city, String zip) {
        return Patient.builder().firstName(first).lastName(last).gender(gender).email(email).phone(phone)
                .address(Address.builder().city(city).state("QC").zip(zip).build()).build();
    }

    private FollowUp followUp(Patient p, Specialty s, LocalDate discharge, int days, String code) {
        QuestionnaireDefinition def = registry.forFollowUp(s).orElseThrow();
        FollowUp f = followUps.save(FollowUp.builder().patient(p).specialty(s).questionnaire(def).dischargeDate(discharge)
                .durationDays(days).endDate(discharge.plusDays(days)).status(FollowUpStatus.ACTIVE)
                .createdAt(Instant.now(clock)).createdBy(ACTOR).build());
        links.issueWithCode(f, code, ACTOR);
        return f;
    }

    private void appt(Patient p, AppointmentType type, Specialty s, LocalDate day, int h, int m, AppointmentStatus finalStatus,
                      Map<String, Object> intake, String code) {
        QuestionnaireDefinition def = registry.forAppointment(type, s).orElseThrow();
        Instant when = ZonedDateTime.of(LocalDateTime.of(day, LocalTime.of(h, m)), clock.getZone()).toInstant();
        Instant now = Instant.now(clock);
        Appointment a = appointments.save(Appointment.builder().patient(p).type(type).specialty(s).questionnaire(def)
                .scheduledAt(when).durationMinutes(30).status(AppointmentStatus.SCHEDULED)
                .createdAt(now).createdBy(ACTOR).updatedAt(now).build());
        if (code != null) {
            links.issueWithCode(a, code, ACTOR);
        } else {
            links.issueForAppointment(a, ACTOR);
        }
        if (intake != null) {
            appointmentService.recordIntakeForSeed(a, when.minusSeconds(86400), intake);
        }
        a.setStatus(finalStatus);
        appointments.save(a);
    }

    private Instant at(LocalDate d, int hour) {
        return ZonedDateTime.of(d, LocalTime.of(hour, 0), clock.getZone()).toInstant();
    }
}
