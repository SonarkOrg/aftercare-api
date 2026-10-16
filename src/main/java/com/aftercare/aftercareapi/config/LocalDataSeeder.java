package com.aftercare.aftercareapi.config;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireRegistry;
import com.aftercare.aftercareapi.Repositories.*;
import com.aftercare.aftercareapi.Services.AccessLinkService;
import com.aftercare.aftercareapi.Services.ActivityService;
import com.aftercare.aftercareapi.Services.AlertService;
import com.aftercare.aftercareapi.Services.AppointmentService;
import com.aftercare.aftercareapi.Services.CheckInService;
import com.aftercare.aftercareapi.Services.DocumentStorage;
import com.aftercare.aftercareapi.Services.HealthNumbers;
import com.aftercare.aftercareapi.Services.PhiCrypto;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds demo data in profile local. Patients, follow-ups, alerts and appointments are seeded only when there are no
 * patients (through the real submission pipeline); the patient-file block (staff, medical info, notes, documents,
 * messages, clinic settings) has its own guard (no staff yet), so it also runs on an already-seeded database.
 */
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
    private final StaffRepository staff;
    private final CareTeamAssignmentRepository careTeam;
    private final EmergencyContactRepository contacts;
    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final PatientProcedureRepository procedures;
    private final DiagnosisRepository diagnoses;
    private final CareNoteRepository notes;
    private final PatientDocumentRepository documents;
    private final PatientMessageRepository messages;
    private final ClinicSettingsRepository clinicSettings;
    private final DocumentStorage storage;
    private final ActivityService activity;
    private final PhiCrypto crypto;

    public LocalDataSeeder(PatientRepository patients, FollowUpRepository followUps, AppointmentRepository appointments,
                           AlertRepository alerts, QuestionnaireRegistry registry, AccessLinkService links,
                           CheckInService checkIns, AppointmentService appointmentService, AlertService alertService,
                           Clock clock, @Value("${aftercare.frontend-base-url:http://localhost:4200}") String frontendBaseUrl,
                           StaffRepository staff, CareTeamAssignmentRepository careTeam, EmergencyContactRepository contacts,
                           AllergyRepository allergies, MedicationRepository medications, PatientProcedureRepository procedures,
                           DiagnosisRepository diagnoses, CareNoteRepository notes, PatientDocumentRepository documents,
                           PatientMessageRepository messages, ClinicSettingsRepository clinicSettings, DocumentStorage storage,
                           ActivityService activity, PhiCrypto crypto) {
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
        this.staff = staff;
        this.careTeam = careTeam;
        this.contacts = contacts;
        this.allergies = allergies;
        this.medications = medications;
        this.procedures = procedures;
        this.diagnoses = diagnoses;
        this.notes = notes;
        this.documents = documents;
        this.messages = messages;
        this.clinicSettings = clinicSettings;
        this.storage = storage;
        this.activity = activity;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (patients.count() == 0) {
            seedPatients();
        }
        if (staff.count() == 0) {
            seedFileData();
        }
        ensureDevStaff();
    }

    /** The dev token's admin and reception logins. Idempotent by sub; creates only what is missing. */
    private void ensureDevStaff() {
        Instant now = Instant.now(clock);
        if (staff.findByAuth0Subject("dev|admin-1").isEmpty()) {
            staff("Dev Admin", StaffRole.OTHER, AccessRole.ADMIN, "dev.admin@example.com", "dev|admin-1", true, now);
        }
        if (staff.findByAuth0Subject("dev|reception-1").isEmpty()) {
            staff("Dev Reception", StaffRole.ADMINISTRATIVE, AccessRole.RECEPTION, "dev.reception@example.com",
                    "dev|reception-1", true, now);
        }
    }

    private void seedPatients() {
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

    // ---- patient file data (staff, medical info, notes, documents, messages, clinic settings)

    private void seedFileData() {
        LocalDate t = LocalDate.now(clock);
        Instant now = Instant.now(clock);

        ClinicSettings cs = clinicSettings.findById(ClinicSettings.ID).orElseGet(ClinicSettings::new);
        cs.setId(ClinicSettings.ID);
        cs.setName("Clinique Saint-Laurent");
        cs.setAddressLine("1234 boulevard Saint-Laurent");
        cs.setCity("Montréal");
        cs.setProvince("QC");
        cs.setPostalCode("H2X 2T1");
        cs.setPhone("514-555-0199");
        cs.setDefaultLanguage(Language.FR);
        cs.setNotifyHigh(true);
        cs.setNotifyMedium(true);
        // Local seed: both channels on (they use the logging senders, nothing leaves the machine), reminders on.
        cs.setEmailEnabled(true);
        cs.setSmsEnabled(true);
        cs.setCheckInReminderEnabled(true);
        cs.setAppointmentReminderEnabled(true);
        clinicSettings.save(cs);

        StaffMember nurse = staff("Dev Nurse", StaffRole.NURSE, AccessRole.NURSE, "dev.nurse@example.com", "dev|nurse-1", true, now);
        StaffMember bouchard = staff("Dr Alain Bouchard", StaffRole.SURGEON, AccessRole.PHYSICIAN, "alain.bouchard@example.com", "auth0|seed-surgeon", true, now);
        staff("Dr Nadia Lefebvre", StaffRole.PHYSICIAN, AccessRole.PHYSICIAN, "nadia.lefebvre@example.com", null, true, now);
        StaffMember isabelle = staff("Isabelle Morin", StaffRole.NURSE, AccessRole.NURSE, "isabelle.morin@example.com", null, true, now);
        staff("Marc Pelletier", StaffRole.PHYSIOTHERAPIST, AccessRole.RECEPTION, "marc.pelletier@example.com", null, false, now);

        int noteCount = 0;
        int docCount = 0;
        int msgCount = 0;

        Patient marie = patients.findByEmail("marie.tremblay@example.com").orElse(null);
        if (marie != null) {
            consent(marie, ContactConsent.GRANTED, NotificationChannel.SMS, Language.FR, now);
            marie.setDateOfBirth(LocalDate.of(1968, 4, 12));
            marie.setHealthNumberEnc(crypto.encrypt("TREM68541212", marie.getId()));
            marie.setHealthNumberMasked(HealthNumbers.mask("TREM68541212"));
            patients.save(marie);
            allergy(marie, "Penicillin", AllergySeverity.SEVERE, "Hives and throat swelling", now);
            allergy(marie, "Latex", AllergySeverity.MILD, "Skin irritation", now);
            medication(marie, "Acetaminophen", "1 g", "every 6 h as needed", now);
            medication(marie, "Enoxaparin", "40 mg", "once daily", now);
            medication(marie, "Metformin", "500 mg", "twice daily", now);
            procedure(marie, "Laparoscopic cholecystectomy", t.minusDays(3), bouchard, now);
            diagnosis(marie, "Symptomatic cholelithiasis", "K80.20", t.minusDays(30), now);
            diagnosis(marie, "Type 2 diabetes", "E11.9", null, now);
            contact(marie, "Luc Tremblay", "Spouse", "514-555-0144", now);
            team(marie, nurse, StaffRole.NURSE, now);
            team(marie, bouchard, StaffRole.SURGEON, now);
            note(marie, at(t.minusDays(1), 10, 5), "dev|nurse-1", "Dev Nurse",
                    "Called patient about wound redness. Mild warmth, no discharge. Advised to keep dressing dry; will review tomorrow's check-in.");
            note(marie, at(t.minusDays(1), 16, 30), "auth0|seed-surgeon", "Dr Alain Bouchard",
                    "Post-op course as expected. Continue enoxaparin 10 days.");
            noteCount += 2;
            document(marie, "Discharge summary", DocumentCategory.DISCHARGE_SUMMARY, "discharge-summary.pdf", at(t.minusDays(3), 14, 0), "dev|nurse-1", "Dev Nurse");
            document(marie, "Surgical consent", DocumentCategory.CONSENT, "consent.pdf", at(t.minusDays(4), 9, 0), "auth0|seed-surgeon", "Dr Alain Bouchard");
            docCount += 2;
            message(marie, MessageSender.STAFF, "dev|nurse-1", "Dev Nurse", at(t.minusDays(1), 10, 10),
                    "Bonjour Mme Tremblay, nous avons vu votre réponse d'hier. Gardez le pansement sec et répondez au suivi demain matin.",
                    null, at(t.minusDays(1), 10, 35));
            message(marie, MessageSender.PATIENT, null, null, at(t.minusDays(1), 10, 40),
                    "Merci. La rougeur est un peu moins marquée ce soir.", at(t.minusDays(1), 11, 0), null);
            message(marie, MessageSender.PATIENT, null, null, at(t, 8, 15), "Est-ce que je peux prendre une douche?", null, null);
            msgCount += 3;
        }

        Patient jean = patients.findByEmail("jean.gagnon@example.com").orElse(null);
        if (jean != null) {
            consent(jean, ContactConsent.GRANTED, NotificationChannel.EMAIL, Language.EN, now);
            jean.setDateOfBirth(LocalDate.of(1959, 11, 3));
            jean.setHealthNumberEnc(crypto.encrypt("GAGJ59110312", jean.getId()));
            jean.setHealthNumberMasked(HealthNumbers.mask("GAGJ59110312"));
            patients.save(jean);
            medication(jean, "Apixaban", "2.5 mg", "twice daily", now);
            procedure(jean, "Total knee arthroplasty (right)", t.minusDays(1), bouchard, now);
            team(jean, isabelle, StaffRole.NURSE, now);
            message(jean, MessageSender.STAFF, null, "Isabelle Morin", at(t.minusDays(1), 15, 0),
                    "Bonjour M. Gagnon, pensez à porter vos bas de compression.", null, null);
            msgCount += 1;
        }

        Patient sophie = patients.findByEmail("sophie.roy@example.com").orElse(null);
        if (sophie != null) {
            consent(sophie, ContactConsent.UNKNOWN, NotificationChannel.EMAIL, null, now);
            sophie.setDateOfBirth(LocalDate.of(1985, 2, 27));
            patients.save(sophie);
            contact(sophie, "Chantal Roy", "Mother", "450-555-0177", now);
            note(sophie, at(t.minusDays(2), 11, 0), "dev|nurse-1", "Dev Nurse", "Pre-op teaching done by phone.");
            noteCount += 1;
        }
        log.info("Seeded file data: staff=5 notes={} documents={} messages={}", noteCount, docCount, msgCount);
    }

    private StaffMember staff(String name, StaffRole role, AccessRole accessRole, String email, String sub, boolean active, Instant now) {
        StaffMember s = new StaffMember();
        s.setDisplayName(name);
        s.setRole(role);
        s.setAccessRole(accessRole);
        s.setEmail(email);
        s.setAuth0Subject(sub);
        s.setActive(active);
        s.setCreatedAt(now);
        s.setCreatedBySub(ACTOR);
        s.setUpdatedAt(now);
        s.setUpdatedBySub(ACTOR);
        return staff.save(s);
    }

    /** Contact preferences of a seeded patient; recorded consents are attributed to Dev Nurse. */
    private void consent(Patient p, ContactConsent consent, NotificationChannel channel, Language language, Instant now) {
        p.setContactConsent(consent);
        p.setPreferredChannel(channel);
        p.setPreferredLanguage(language);
        if (consent != ContactConsent.UNKNOWN) {
            p.setContactConsentUpdatedAt(now);
            p.setContactConsentUpdatedBySub("dev|nurse-1");
            p.setContactConsentUpdatedByName("Dev Nurse");
        }
    }

    private <T extends ClinicalItem> T stamp(T item, Patient p, Instant now) {
        item.setPatient(p);
        item.setCreatedAt(now);
        item.setCreatedBySub(ACTOR);
        item.setCreatedByName("Dev Nurse");
        item.setUpdatedAt(now);
        item.setUpdatedBySub(ACTOR);
        item.setUpdatedByName("Dev Nurse");
        return item;
    }

    private void allergy(Patient p, String substance, AllergySeverity severity, String reaction, Instant now) {
        Allergy a = stamp(new Allergy(), p, now);
        a.setSubstance(substance);
        a.setSeverity(severity);
        a.setReaction(reaction);
        allergies.save(a);
    }

    private void medication(Patient p, String name, String dose, String frequency, Instant now) {
        Medication m = stamp(new Medication(), p, now);
        m.setName(name);
        m.setDose(dose);
        m.setFrequency(frequency);
        medications.save(m);
    }

    private void procedure(Patient p, String name, LocalDate on, StaffMember surgeon, Instant now) {
        PatientProcedure x = stamp(new PatientProcedure(), p, now);
        x.setName(name);
        x.setPerformedOn(on);
        x.setSurgeon(surgeon);
        procedures.save(x);
    }

    private void diagnosis(Patient p, String description, String icd10, LocalDate on, Instant now) {
        Diagnosis d = stamp(new Diagnosis(), p, now);
        d.setDescription(description);
        d.setIcd10Code(icd10);
        d.setDiagnosedOn(on);
        diagnoses.save(d);
    }

    private void contact(Patient p, String name, String relationship, String phone, Instant now) {
        EmergencyContact c = new EmergencyContact();
        c.setPatient(p);
        c.setName(name);
        c.setRelationship(relationship);
        c.setPhone(phone);
        c.setPreferredLanguage(Language.FR);
        c.setCreatedAt(now);
        c.setCreatedBySub(ACTOR);
        c.setCreatedByName("Dev Nurse");
        c.setUpdatedAt(now);
        c.setUpdatedBySub(ACTOR);
        c.setUpdatedByName("Dev Nurse");
        contacts.save(c);
    }

    private void team(Patient p, StaffMember s, StaffRole role, Instant now) {
        CareTeamAssignment a = new CareTeamAssignment();
        a.setPatient(p);
        a.setStaff(s);
        a.setRole(role);
        a.setAssignedAt(now);
        a.setAssignedBySub(ACTOR);
        a.setAssignedByName("Dev Nurse");
        careTeam.save(a);
    }

    private void note(Patient p, Instant when, String sub, String name, String body) {
        CareNote n = new CareNote();
        n.setPatient(p);
        n.setBody(body);
        n.setNotedAt(when);
        n.setCreatedAt(when);
        n.setAuthorSub(sub);
        n.setAuthorName(name);
        n = notes.save(n);
        activity.record(p.getId(), ActivityKind.NOTE_ADDED, when, ActorType.STAFF, sub, name, n.getId(), null, "Note");
    }

    private void document(Patient p, String title, DocumentCategory category, String filename, Instant when, String sub, String name) {
        byte[] bytes = pdf(title);
        PatientDocument d = new PatientDocument();
        d.setPatient(p);
        d.setTitle(title);
        d.setCategory(category);
        d.setContentType("application/pdf");
        d.setSizeBytes(bytes.length);
        d.setSha256(sha256(bytes));
        d.setOriginalFilename(filename);
        d.setStorageKey(storage.store(bytes));
        d.setUploadedAt(when);
        d.setUploadedBySub(sub);
        d.setUploadedByName(name);
        d = documents.save(d);
        String cat = category.name().toLowerCase().replace('_', ' ');
        activity.record(p.getId(), ActivityKind.DOCUMENT_UPLOADED, when, ActorType.STAFF, sub, name, d.getId(), null,
                "Document uploaded: " + title + " (" + Character.toUpperCase(cat.charAt(0)) + cat.substring(1) + ")");
    }

    private void message(Patient p, MessageSender sender, String staffSub, String staffName, Instant when, String body,
                         Instant readByStaff, Instant readByPatient) {
        PatientMessage m = new PatientMessage();
        m.setPatient(p);
        m.setSender(sender);
        m.setBody(body);
        m.setCreatedAt(when);
        m.setStaffSub(staffSub);
        m.setStaffName(staffName);
        m.setReadByStaffAt(readByStaff);
        m.setReadByPatientAt(readByPatient);
        m = messages.save(m);
        String preview = body.length() <= 140 ? body : body.substring(0, 140) + "…";
        if (sender == MessageSender.PATIENT) {
            activity.record(p.getId(), ActivityKind.MESSAGE_FROM_PATIENT, when, ActorType.PATIENT, null, null, m.getId(), null, preview);
        } else {
            activity.record(p.getId(), ActivityKind.MESSAGE_TO_PATIENT, when, ActorType.STAFF, staffSub, staffName, m.getId(), null, preview);
        }
    }

    /** A minimal one-page PDF (about 400 bytes) carrying the title text. */
    static byte[] pdf(String title) {
        String text = title.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String stream = "BT /F1 18 Tf 72 720 Td (" + text + ") Tj ET";
        List<String> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>");
        objs.add("<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objs.size(); i++) {
            offsets.add(sb.length());
            sb.append(i + 1).append(" 0 obj\n").append(objs.get(i)).append("\nendobj\n");
        }
        int xref = sb.length();
        sb.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) {
            sb.append(String.format("%010d 00000 n \n", off));
        }
        sb.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant at(LocalDate d, int hour, int minute) {
        return ZonedDateTime.of(d, LocalTime.of(hour, minute), clock.getZone()).toInstant();
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
