package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Repositories.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Medical information of a patient: the four soft-deletable lists and the encrypted RAMQ number.
 * Never logs or audits values: only ids, counts and field names.
 */
@Service
public class PatientFileService {

    private static final Logger log = LoggerFactory.getLogger(PatientFileService.class);
    private static final Pattern ICD10 = Pattern.compile("^[A-Z][0-9][0-9A-Z](\\.[0-9A-Z]{1,4})?$");
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final Comparator<String> BY_TEXT = Texts.collation();

    private final PatientRepository patients;
    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final PatientProcedureRepository procedures;
    private final DiagnosisRepository diagnoses;
    private final StaffRepository staff;
    private final PhiCrypto crypto;
    private final AuditService audit;
    private final Clock clock;

    public PatientFileService(PatientRepository patients, AllergyRepository allergies, MedicationRepository medications,
                              PatientProcedureRepository procedures, DiagnosisRepository diagnoses, StaffRepository staff,
                              PhiCrypto crypto, AuditService audit, Clock clock) {
        this.patients = patients;
        this.allergies = allergies;
        this.medications = medications;
        this.procedures = procedures;
        this.diagnoses = diagnoses;
        this.staff = staff;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- read

    @Transactional(readOnly = true)
    public PatientMedicalDTO medical(Long patientId) {
        Patient p = patient(patientId);
        List<AllergyDTO> a = allergies.findByPatientIdAndDeletedAtIsNull(patientId).stream()
                .sorted(Comparator.comparing(Allergy::getSeverity)
                        .thenComparing(Allergy::getSubstance, BY_TEXT).thenComparing(Allergy::getId))
                .map(this::toDto).toList();
        List<MedicationDTO> m = medications.findByPatientIdAndDeletedAtIsNull(patientId).stream()
                .sorted(Comparator.comparing(Medication::getName, BY_TEXT).thenComparing(Medication::getId))
                .map(this::toDto).toList();
        List<ProcedureDTO> pr = procedures.findActiveWithSurgeon(patientId).stream()
                .sorted(Comparator.comparing(PatientProcedure::getPerformedOn).reversed()
                        .thenComparing(Comparator.comparing(PatientProcedure::getId).reversed()))
                .map(this::toDto).toList();
        List<DiagnosisDTO> d = diagnoses.findByPatientIdAndDeletedAtIsNull(patientId).stream()
                .sorted(Comparator.comparing(Diagnosis::getDiagnosedOn, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
                        .thenComparing(Comparator.comparing(Diagnosis::getId).reversed()))
                .map(this::toDto).toList();
        return new PatientMedicalDTO(patientId, p.getDateOfBirth(), p.getHealthNumberMasked(), a, m, pr, d);
    }

    // ---- health number

    @Transactional
    public HealthNumberMaskedDTO setHealthNumber(Long patientId, HealthNumberRequest req, Actor actor) {
        Patient p = patient(patientId);
        String normalized = HealthNumbers.normalize(req == null ? null : req.healthNumber());
        if (!HealthNumbers.isValid(normalized)) {
            throw ApiException.validation("healthNumber", "must be a valid RAMQ number (4 letters, 8 digits)");
        }
        p.setHealthNumberEnc(crypto.encrypt(normalized, patientId));
        p.setHealthNumberMasked(HealthNumbers.mask(normalized));
        patients.saveAndFlush(p);
        audit.recordChange(actor, AuditAction.HEALTH_NUMBER_SET, patientId, AuditResource.HEALTH_NUMBER, null, null);
        log.info("Health number set patient={}", patientId);
        return new HealthNumberMaskedDTO(p.getHealthNumberMasked());
    }

    @Transactional
    public void clearHealthNumber(Long patientId, Actor actor) {
        Patient p = patient(patientId);
        if (p.getHealthNumberEnc() == null && p.getHealthNumberMasked() == null) {
            return;
        }
        p.setHealthNumberEnc(null);
        p.setHealthNumberMasked(null);
        patients.saveAndFlush(p);
        audit.recordChange(actor, AuditAction.HEALTH_NUMBER_CLEARED, patientId, AuditResource.HEALTH_NUMBER, null, null);
        log.info("Health number cleared patient={}", patientId);
    }

    /** The decrypted number plus the validated reason, for the caller to audit after this transaction commits. */
    public record Revealed(HealthNumberRevealDTO dto, String reason) {
    }

    @Transactional(readOnly = true)
    public Revealed reveal(Long patientId, RevealRequest req) {
        Patient p = patient(patientId);
        Fields f = new Fields();
        String reason = f.body("reason", req == null ? null : req.reason(), 3, 200);
        f.check();
        if (p.getHealthNumberEnc() == null) {
            throw ApiException.notFound("No health number on file");
        }
        String plain = crypto.decrypt(p.getHealthNumberEnc(), patientId);
        log.info("Health number revealed patient={}", patientId);
        return new Revealed(new HealthNumberRevealDTO(plain, p.getHealthNumberMasked(), Instant.now(clock)), reason);
    }

    // ---- allergies

    @Transactional
    public AllergyDTO addAllergy(Long patientId, AllergyRequest r, Actor actor) {
        Patient p = patient(patientId);
        Allergy a = new Allergy();
        applyAllergy(a, r);
        stampNew(a, p, actor);
        a = allergies.saveAndFlush(a);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_ADDED, patientId, AuditResource.ALLERGY, a.getId(), null);
        return toDto(a);
    }

    @Transactional
    public AllergyDTO updateAllergy(Long patientId, Long itemId, AllergyRequest r, Actor actor) {
        patient(patientId);
        Allergy a = allergies.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        applyAllergy(a, r);
        stampUpdate(a, actor);
        a = allergies.saveAndFlush(a);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_UPDATED, patientId, AuditResource.ALLERGY, a.getId(), null);
        return toDto(a);
    }

    @Transactional
    public void removeAllergy(Long patientId, Long itemId, Actor actor) {
        patient(patientId);
        Allergy a = allergies.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        softDelete(a, actor);
        allergies.saveAndFlush(a);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_REMOVED, patientId, AuditResource.ALLERGY, itemId, null);
    }

    private void applyAllergy(Allergy a, AllergyRequest r) {
        Fields f = new Fields();
        String substance = f.required("substance", r.substance(), 200);
        String reaction = f.optional("reaction", r.reaction(), 500);
        f.check();
        a.setSubstance(substance);
        a.setSeverity(r.severity());
        a.setReaction(reaction);
    }

    // ---- medications

    @Transactional
    public MedicationDTO addMedication(Long patientId, MedicationRequest r, Actor actor) {
        Patient p = patient(patientId);
        Medication m = new Medication();
        applyMedication(m, r);
        stampNew(m, p, actor);
        m = medications.saveAndFlush(m);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_ADDED, patientId, AuditResource.MEDICATION, m.getId(), null);
        return toDto(m);
    }

    @Transactional
    public MedicationDTO updateMedication(Long patientId, Long itemId, MedicationRequest r, Actor actor) {
        patient(patientId);
        Medication m = medications.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        applyMedication(m, r);
        stampUpdate(m, actor);
        m = medications.saveAndFlush(m);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_UPDATED, patientId, AuditResource.MEDICATION, m.getId(), null);
        return toDto(m);
    }

    @Transactional
    public void removeMedication(Long patientId, Long itemId, Actor actor) {
        patient(patientId);
        Medication m = medications.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        softDelete(m, actor);
        medications.saveAndFlush(m);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_REMOVED, patientId, AuditResource.MEDICATION, itemId, null);
    }

    private void applyMedication(Medication m, MedicationRequest r) {
        Fields f = new Fields();
        String name = f.required("name", r.name(), 200);
        String dose = f.optional("dose", r.dose(), 100);
        String frequency = f.optional("frequency", r.frequency(), 100);
        if (r.startDate() != null && r.startDate().isAfter(LocalDate.now(clock))) {
            f.error("startDate", "must not be in the future");
        }
        f.check();
        m.setName(name);
        m.setDose(dose);
        m.setFrequency(frequency);
        m.setStartDate(r.startDate());
    }

    // ---- procedures

    @Transactional
    public ProcedureDTO addProcedure(Long patientId, ProcedureRequest r, Actor actor) {
        Patient p = patient(patientId);
        PatientProcedure x = new PatientProcedure();
        applyProcedure(x, r);
        stampNew(x, p, actor);
        x = procedures.saveAndFlush(x);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_ADDED, patientId, AuditResource.PROCEDURE, x.getId(), null);
        return toDto(x);
    }

    @Transactional
    public ProcedureDTO updateProcedure(Long patientId, Long itemId, ProcedureRequest r, Actor actor) {
        patient(patientId);
        PatientProcedure x = procedures.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        applyProcedure(x, r);
        stampUpdate(x, actor);
        x = procedures.saveAndFlush(x);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_UPDATED, patientId, AuditResource.PROCEDURE, x.getId(), null);
        return toDto(x);
    }

    @Transactional
    public void removeProcedure(Long patientId, Long itemId, Actor actor) {
        patient(patientId);
        PatientProcedure x = procedures.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        softDelete(x, actor);
        procedures.saveAndFlush(x);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_REMOVED, patientId, AuditResource.PROCEDURE, itemId, null);
    }

    private void applyProcedure(PatientProcedure x, ProcedureRequest r) {
        Fields f = new Fields();
        String name = f.required("name", r.name(), 200);
        String surgeonName = f.optional("surgeonName", r.surgeonName(), 120);
        LocalDate today = LocalDate.now(clock);
        if (r.performedOn() == null) {
            f.error("performedOn", "must not be null");
        } else if (r.performedOn().isBefore(MIN_DATE) || r.performedOn().isAfter(today.plusDays(365))) {
            f.error("performedOn", "must be between 1900-01-01 and one year from today");
        }
        f.check();
        StaffMember surgeon = null;
        if (r.surgeonStaffId() != null) {
            surgeon = staff.findById(r.surgeonStaffId()).orElseThrow(() -> ApiException.notFound("Staff member not found"));
            boolean unchanged = x.getSurgeon() != null && x.getSurgeon().getId().equals(surgeon.getId());
            if (!surgeon.isActive() && !unchanged) {
                throw ApiException.conflict("STAFF_INACTIVE", "Staff member is inactive");
            }
            surgeonName = null;
        }
        x.setName(name);
        x.setPerformedOn(r.performedOn());
        x.setSurgeon(surgeon);
        x.setSurgeonName(surgeonName);
    }

    // ---- diagnoses

    @Transactional
    public DiagnosisDTO addDiagnosis(Long patientId, DiagnosisRequest r, Actor actor) {
        Patient p = patient(patientId);
        Diagnosis d = new Diagnosis();
        applyDiagnosis(d, r);
        stampNew(d, p, actor);
        d = diagnoses.saveAndFlush(d);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_ADDED, patientId, AuditResource.DIAGNOSIS, d.getId(), null);
        return toDto(d);
    }

    @Transactional
    public DiagnosisDTO updateDiagnosis(Long patientId, Long itemId, DiagnosisRequest r, Actor actor) {
        patient(patientId);
        Diagnosis d = diagnoses.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        applyDiagnosis(d, r);
        stampUpdate(d, actor);
        d = diagnoses.saveAndFlush(d);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_UPDATED, patientId, AuditResource.DIAGNOSIS, d.getId(), null);
        return toDto(d);
    }

    @Transactional
    public void removeDiagnosis(Long patientId, Long itemId, Actor actor) {
        patient(patientId);
        Diagnosis d = diagnoses.findByIdAndPatientIdAndDeletedAtIsNull(itemId, patientId).orElseThrow(PatientFileService::notFound);
        softDelete(d, actor);
        diagnoses.saveAndFlush(d);
        audit.recordChange(actor, AuditAction.MEDICAL_ITEM_REMOVED, patientId, AuditResource.DIAGNOSIS, itemId, null);
    }

    private void applyDiagnosis(Diagnosis d, DiagnosisRequest r) {
        Fields f = new Fields();
        String description = f.required("description", r.description(), 300);
        String code = f.optional("icd10Code", r.icd10Code(), 16);
        if (code != null) {
            code = code.toUpperCase();
            if (!ICD10.matcher(code).matches()) {
                f.error("icd10Code", "must be a valid ICD-10 code");
            }
        }
        if (r.diagnosedOn() != null && r.diagnosedOn().isAfter(LocalDate.now(clock))) {
            f.error("diagnosedOn", "must not be in the future");
        }
        f.check();
        d.setDescription(description);
        d.setIcd10Code(code);
        d.setDiagnosedOn(r.diagnosedOn());
    }

    // ---- helpers

    private Patient patient(Long id) {
        return patients.findById(id).orElseThrow(PatientNotFoundException::new);
    }

    private static ApiException notFound() {
        return ApiException.notFound("Not found");
    }

    private void stampNew(ClinicalItem i, Patient p, Actor actor) {
        Instant now = Instant.now(clock);
        i.setPatient(p);
        i.setCreatedAt(now);
        i.setCreatedBySub(actor.sub());
        i.setCreatedByName(actor.name());
        i.setUpdatedAt(now);
        i.setUpdatedBySub(actor.sub());
        i.setUpdatedByName(actor.name());
    }

    private void stampUpdate(ClinicalItem i, Actor actor) {
        i.setUpdatedAt(Instant.now(clock));
        i.setUpdatedBySub(actor.sub());
        i.setUpdatedByName(actor.name());
    }

    private void softDelete(ClinicalItem i, Actor actor) {
        Instant now = Instant.now(clock);
        i.setDeletedAt(now);
        i.setDeletedBySub(actor.sub());
        i.setDeletedByName(actor.name());
        i.setUpdatedAt(now);
        i.setUpdatedBySub(actor.sub());
        i.setUpdatedByName(actor.name());
    }

    private AllergyDTO toDto(Allergy a) {
        return new AllergyDTO(a.getId(), a.getSubstance(), a.getSeverity(), a.getReaction(), a.getCreatedAt(),
                a.getCreatedByName(), a.getUpdatedAt(), a.getUpdatedByName());
    }

    private MedicationDTO toDto(Medication m) {
        return new MedicationDTO(m.getId(), m.getName(), m.getDose(), m.getFrequency(), m.getStartDate(), m.getCreatedAt(),
                m.getCreatedByName(), m.getUpdatedAt(), m.getUpdatedByName());
    }

    private ProcedureDTO toDto(PatientProcedure x) {
        StaffMember s = x.getSurgeon();
        return new ProcedureDTO(x.getId(), x.getName(), x.getPerformedOn(), s == null ? null : s.getId(),
                s == null ? x.getSurgeonName() : s.getDisplayName(), x.getCreatedAt(), x.getCreatedByName(),
                x.getUpdatedAt(), x.getUpdatedByName());
    }

    private DiagnosisDTO toDto(Diagnosis d) {
        return new DiagnosisDTO(d.getId(), d.getDescription(), d.getIcd10Code(), d.getDiagnosedOn(), d.getCreatedAt(),
                d.getCreatedByName(), d.getUpdatedAt(), d.getUpdatedByName());
    }
}
