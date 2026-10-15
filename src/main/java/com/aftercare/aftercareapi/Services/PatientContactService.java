package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.CareTeamAssignment;
import com.aftercare.aftercareapi.Models.Dto.CareTeamAssignRequest;
import com.aftercare.aftercareapi.Models.Dto.CareTeamMemberDTO;
import com.aftercare.aftercareapi.Models.Dto.EmergencyContactDTO;
import com.aftercare.aftercareapi.Models.Dto.EmergencyContactRequest;
import com.aftercare.aftercareapi.Models.EmergencyContact;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.StaffMember;
import com.aftercare.aftercareapi.Repositories.CareTeamAssignmentRepository;
import com.aftercare.aftercareapi.Repositories.EmergencyContactRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import com.aftercare.aftercareapi.Repositories.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Emergency contacts (hard delete) and the patient's care team. */
@Service
public class PatientContactService {

    private final PatientRepository patients;
    private final EmergencyContactRepository contacts;
    private final CareTeamAssignmentRepository careTeam;
    private final StaffRepository staff;
    private final AuditService audit;
    private final Clock clock;

    public PatientContactService(PatientRepository patients, EmergencyContactRepository contacts,
                                 CareTeamAssignmentRepository careTeam, StaffRepository staff, AuditService audit, Clock clock) {
        this.patients = patients;
        this.contacts = contacts;
        this.careTeam = careTeam;
        this.staff = staff;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- emergency contacts

    @Transactional(readOnly = true)
    public List<EmergencyContactDTO> listContacts(Long patientId) {
        patient(patientId);
        return contacts.findByPatientIdOrderByIdAsc(patientId).stream().map(this::toDto).toList();
    }

    @Transactional
    public EmergencyContactDTO addContact(Long patientId, EmergencyContactRequest r, Actor actor) {
        Patient p = patient(patientId);
        EmergencyContact c = new EmergencyContact();
        apply(c, r);
        Instant now = Instant.now(clock);
        c.setPatient(p);
        c.setCreatedAt(now);
        c.setCreatedBySub(actor.sub());
        c.setCreatedByName(actor.name());
        stampUpdate(c, actor, now);
        c = contacts.saveAndFlush(c);
        audit.recordChange(actor, AuditAction.CONTACT_ADDED, patientId, AuditResource.EMERGENCY_CONTACT, c.getId(), null);
        return toDto(c);
    }

    @Transactional
    public EmergencyContactDTO updateContact(Long patientId, Long contactId, EmergencyContactRequest r, Actor actor) {
        patient(patientId);
        EmergencyContact c = contacts.findByIdAndPatientId(contactId, patientId).orElseThrow(PatientContactService::notFound);
        apply(c, r);
        stampUpdate(c, actor, Instant.now(clock));
        c = contacts.saveAndFlush(c);
        audit.recordChange(actor, AuditAction.CONTACT_UPDATED, patientId, AuditResource.EMERGENCY_CONTACT, c.getId(), null);
        return toDto(c);
    }

    @Transactional
    public void removeContact(Long patientId, Long contactId, Actor actor) {
        patient(patientId);
        EmergencyContact c = contacts.findByIdAndPatientId(contactId, patientId).orElseThrow(PatientContactService::notFound);
        contacts.delete(c);
        contacts.flush();
        audit.recordChange(actor, AuditAction.CONTACT_REMOVED, patientId, AuditResource.EMERGENCY_CONTACT, contactId, null);
    }

    private void apply(EmergencyContact c, EmergencyContactRequest r) {
        Fields f = new Fields();
        String name = f.required("name", r.name(), 120);
        String relationship = f.required("relationship", r.relationship(), 60);
        String phone = f.phone("phone", r.phone(), true);
        f.check();
        c.setName(name);
        c.setRelationship(relationship);
        c.setPhone(phone);
        c.setPreferredLanguage(r.preferredLanguage());
    }

    private void stampUpdate(EmergencyContact c, Actor actor, Instant now) {
        c.setUpdatedAt(now);
        c.setUpdatedBySub(actor.sub());
        c.setUpdatedByName(actor.name());
    }

    private EmergencyContactDTO toDto(EmergencyContact c) {
        return new EmergencyContactDTO(c.getId(), c.getName(), c.getRelationship(), c.getPhone(), c.getPreferredLanguage(),
                c.getCreatedAt(), c.getCreatedByName(), c.getUpdatedAt(), c.getUpdatedByName());
    }

    // ---- care team

    @Transactional(readOnly = true)
    public List<CareTeamMemberDTO> listCareTeam(Long patientId) {
        patient(patientId);
        return careTeam.findByPatientIdWithStaff(patientId).stream()
                .sorted(Comparator.comparing(CareTeamAssignment::getRole)
                        .thenComparing(a -> a.getStaff().getDisplayName(), Texts.collation())
                        .thenComparing(CareTeamAssignment::getId))
                .map(this::toDto).toList();
    }

    @Transactional
    public CareTeamMemberDTO assign(Long patientId, CareTeamAssignRequest r, Actor actor) {
        Patient p = patient(patientId);
        StaffMember s = staff.findById(r.staffId()).orElseThrow(() -> ApiException.notFound("Staff member not found"));
        if (!s.isActive()) {
            throw ApiException.conflict("STAFF_INACTIVE", "Staff member is inactive");
        }
        if (careTeam.existsByPatientIdAndStaffId(patientId, s.getId())) {
            throw ApiException.conflict("ALREADY_ASSIGNED", "Staff member is already on the care team");
        }
        CareTeamAssignment a = new CareTeamAssignment();
        a.setPatient(p);
        a.setStaff(s);
        a.setRole(r.role() == null ? s.getRole() : r.role());
        a.setAssignedAt(Instant.now(clock));
        a.setAssignedBySub(actor.sub());
        a.setAssignedByName(actor.name());
        a = careTeam.saveAndFlush(a);
        audit.recordChange(actor, AuditAction.CARE_TEAM_ASSIGNED, patientId, AuditResource.CARE_TEAM, a.getId(), null);
        return toDto(a);
    }

    @Transactional
    public void unassign(Long patientId, Long assignmentId, Actor actor) {
        patient(patientId);
        CareTeamAssignment a = careTeam.findByIdAndPatientId(assignmentId, patientId).orElseThrow(PatientContactService::notFound);
        careTeam.delete(a);
        careTeam.flush();
        audit.recordChange(actor, AuditAction.CARE_TEAM_UNASSIGNED, patientId, AuditResource.CARE_TEAM, assignmentId, null);
    }

    private CareTeamMemberDTO toDto(CareTeamAssignment a) {
        StaffMember s = a.getStaff();
        return new CareTeamMemberDTO(a.getId(), s.getId(), s.getDisplayName(), a.getRole(), s.isActive(), a.getAssignedAt(),
                a.getAssignedByName());
    }

    private Patient patient(Long id) {
        return patients.findById(id).orElseThrow(PatientNotFoundException::new);
    }

    private static ApiException notFound() {
        return ApiException.notFound("Not found");
    }
}
