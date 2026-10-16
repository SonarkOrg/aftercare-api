package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Mappers.PatientMapper;
import com.aftercare.aftercareapi.Models.Dto.FollowUpDTO;
import com.aftercare.aftercareapi.Models.Dto.PatientSummaryDTO;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.PatientDTO;
import com.aftercare.aftercareapi.Repositories.*;
import com.aftercare.aftercareapi.Repositories.AppointmentRepository;
import com.aftercare.aftercareapi.Repositories.FollowUpRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.ContactConsent;
import com.aftercare.aftercareapi.Enums.NotificationChannel;
import com.aftercare.aftercareapi.Enums.Permission;
import com.aftercare.aftercareapi.config.security.CurrentStaff;
import com.aftercare.aftercareapi.Enums.AuditResource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PatientService {

    private static final Actor CONTACT_CHANGE_ACTOR = new Actor("system:contact-change", null);
    private static final Logger log = LoggerFactory.getLogger(PatientService.class);

    private final PatientRepository patientRepository;

    private final FollowUpRepository followUpRepository;
    private final AppointmentRepository appointmentRepository;
    private final AlertRepository alertRepository;
    private final FollowUpService followUpService;
    private final AllergyRepository allergyRepository;
    private final MedicationRepository medicationRepository;
    private final PatientProcedureRepository procedureRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final CareNoteRepository noteRepository;
    private final PatientDocumentRepository documentRepository;
    private final EmergencyContactRepository contactRepository;
    private final CareTeamAssignmentRepository careTeamRepository;
    private final PatientMessageRepository messageRepository;
    private final PatientActivityRepository activityRepository;
    private final NotificationOutboxRepository outboxRepository;
    private final AuditService audit;
    private final Clock clock;

    public PatientService(PatientRepository patientRepository, FollowUpRepository followUpRepository,
                          AppointmentRepository appointmentRepository, AlertRepository alertRepository,
                          FollowUpService followUpService, AllergyRepository allergyRepository,
                          MedicationRepository medicationRepository, PatientProcedureRepository procedureRepository,
                          DiagnosisRepository diagnosisRepository, CareNoteRepository noteRepository,
                          PatientDocumentRepository documentRepository, EmergencyContactRepository contactRepository,
                          CareTeamAssignmentRepository careTeamRepository, PatientMessageRepository messageRepository,
                          PatientActivityRepository activityRepository, NotificationOutboxRepository outboxRepository,
                          AuditService audit, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.allergyRepository = allergyRepository;
        this.medicationRepository = medicationRepository;
        this.procedureRepository = procedureRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.noteRepository = noteRepository;
        this.documentRepository = documentRepository;
        this.contactRepository = contactRepository;
        this.careTeamRepository = careTeamRepository;
        this.messageRepository = messageRepository;
        this.activityRepository = activityRepository;
        this.audit = audit;
        this.clock = clock;
        this.patientRepository = patientRepository;
        this.followUpRepository = followUpRepository;
        this.appointmentRepository = appointmentRepository;
        this.alertRepository = alertRepository;
        this.followUpService = followUpService;
    }

    /** Patients ordered by id with their ACTIVE-status follow-up and unresolved alert count. */
    @Transactional(readOnly = true)
    public List<PatientSummaryDTO> getPatientSummaries() {
        List<Patient> patients = patientRepository.findAll().stream()
                .sorted(Comparator.comparing(Patient::getId)).toList();
        if (!CurrentStaff.has(Permission.CLINICAL_READ)) {
            // Reception: the follow-up (its specialty is surgery information) and the alert count are withheld,
            // and their queries are skipped entirely.
            return patients.stream()
                    .map(p -> PatientSummaryDTO.from(PatientMapper.INSTANCE.patientToDTO(p), null, null))
                    .toList();
        }
        Map<Long, FollowUpDTO> active = followUpService.activeByPatient();
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : alertRepository.countUnresolvedByPatient()) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return patients.stream()
                .map(p -> PatientSummaryDTO.from(PatientMapper.INSTANCE.patientToDTO(p), active.get(p.getId()),
                        counts.getOrDefault(p.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public PatientDTO getPatientById(Long id) throws PatientNotFoundException {
        return patientRepository.findById(id)
                .map(PatientMapper.INSTANCE::patientToDTO)
                .orElseThrow(() -> new PatientNotFoundException());
    }

    @Transactional
    public PatientDTO addPatient(PatientDTO patientDTO) {
        validateDateOfBirth(patientDTO.getDateOfBirth());
        Patient entity = PatientMapper.INSTANCE.patientDTOToEntity(patientDTO);
        entity.setPreferredChannel(NotificationChannel.EMAIL);
        entity.setPreferredLanguage(null);
        entity.setContactConsent(ContactConsent.UNKNOWN);
        Patient saved = patientRepository.save(entity);
        log.info("Created patient with ID {}", saved.getId());
        return PatientMapper.INSTANCE.patientToDTO(saved);
    }

    /** Full replace of the demographic fields (including the optional date of birth). Audits the changed field names. */
    @Transactional
    public PatientDTO update(Long id, PatientDTO dto, Actor actor) {
        Patient p = patientRepository.findById(id).orElseThrow(PatientNotFoundException::new);
        validateDateOfBirth(dto.getDateOfBirth());
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(p.getFirstName(), dto.getFirstName())) changed.add("firstName");
        if (!Objects.equals(p.getLastName(), dto.getLastName())) changed.add("lastName");
        if (!Objects.equals(p.getEmail(), dto.getEmail())) changed.add("email");
        if (!Objects.equals(p.getPhone(), dto.getPhone())) changed.add("phone");
        if (!Objects.equals(p.getGender(), dto.getGender())) changed.add("gender");
        if (!Objects.equals(p.getAddress(), dto.getAddress())) changed.add("address");
        if (!Objects.equals(p.getDateOfBirth(), dto.getDateOfBirth())) changed.add("dateOfBirth");
        boolean emailChanged = changed.contains("email");
        boolean phoneChanged = changed.contains("phone");
        p.setFirstName(dto.getFirstName());
        p.setLastName(dto.getLastName());
        p.setEmail(dto.getEmail());
        p.setPhone(dto.getPhone());
        p.setGender(dto.getGender());
        p.setAddress(dto.getAddress());
        p.setDateOfBirth(dto.getDateOfBirth());
        if ((emailChanged || phoneChanged) && p.getContactConsent() == ContactConsent.GRANTED) {
            // Consent was given for the old address/number: it must be re-collected, and queued messages must not go out.
            p.setContactConsent(ContactConsent.UNKNOWN);
            p.setContactConsentUpdatedAt(Instant.now(clock));
            p.setContactConsentUpdatedBySub(CONTACT_CHANGE_ACTOR.sub());
            p.setContactConsentUpdatedByName(CONTACT_CHANGE_ACTOR.name());
            audit.recordChange(CONTACT_CHANGE_ACTOR, AuditAction.CONTACT_PREFERENCES_UPDATED, id, AuditResource.PATIENT, id,
                    "contactConsent");
        }
        patientRepository.saveAndFlush(p);
        if (emailChanged || phoneChanged) {
            Instant now = Instant.now(clock);
            if (emailChanged) outboxRepository.cancelPending(id, NotificationChannel.EMAIL, now);
            if (phoneChanged) outboxRepository.cancelPending(id, NotificationChannel.SMS, now);
        }
        if (!changed.isEmpty()) {
            audit.recordChange(actor, AuditAction.PATIENT_UPDATED, id, AuditResource.PATIENT, id, String.join(",", changed));
        }
        log.info("Updated patient with ID {}", id);
        return PatientMapper.INSTANCE.patientToDTO(p);
    }

    private void validateDateOfBirth(LocalDate dob) {
        if (dob == null) {
            return;
        }
        if (!dob.isBefore(LocalDate.now(clock))) {
            throw ApiException.validation("dateOfBirth", "must be in the past");
        }
        if (dob.isBefore(LocalDate.of(1900, 1, 1))) {
            throw ApiException.validation("dateOfBirth", "must not be before 1900-01-01");
        }
    }

    @Transactional
    public void deletePatientById(Long id, Actor actor) throws PatientNotFoundException {
        if (!patientRepository.existsById(id)) {
            throw new PatientNotFoundException();
        }
        if (followUpRepository.existsByPatientId(id) || appointmentRepository.existsByPatientId(id)) {
            throw ApiException.conflict("PATIENT_HAS_RECORDS", "Patient has follow-ups or appointments and cannot be deleted");
        }
        if (allergyRepository.existsByPatientId(id) || medicationRepository.existsByPatientId(id)
                || procedureRepository.existsByPatientId(id) || diagnosisRepository.existsByPatientId(id)
                || noteRepository.existsByPatientId(id) || documentRepository.existsByPatientId(id)
                || contactRepository.existsByPatientId(id) || careTeamRepository.existsByPatientId(id)
                || messageRepository.existsByPatientId(id)) {
            throw ApiException.conflict("PATIENT_HAS_RECORDS", "Patient has records (notes, documents, messages, medical items, contacts or care team) and cannot be deleted");
        }
        outboxRepository.deleteByPatientId(id);
        patientRepository.deleteById(id);
        patientRepository.flush();
        activityRepository.deleteByPatientId(id);
        audit.recordChange(actor, AuditAction.PATIENT_DELETED, id, AuditResource.PATIENT, id, null);
        log.info("Deleted patient with ID {}", id);
    }
}
