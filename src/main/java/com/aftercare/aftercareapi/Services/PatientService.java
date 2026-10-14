package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Mappers.PatientMapper;
import com.aftercare.aftercareapi.Models.Dto.FollowUpDTO;
import com.aftercare.aftercareapi.Models.Dto.PatientSummaryDTO;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.PatientDTO;
import com.aftercare.aftercareapi.Repositories.AlertRepository;
import com.aftercare.aftercareapi.Repositories.AppointmentRepository;
import com.aftercare.aftercareapi.Repositories.FollowUpRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);

    private final PatientRepository patientRepository;

    private final FollowUpRepository followUpRepository;
    private final AppointmentRepository appointmentRepository;
    private final AlertRepository alertRepository;
    private final FollowUpService followUpService;

    public PatientService(PatientRepository patientRepository, FollowUpRepository followUpRepository,
                          AppointmentRepository appointmentRepository, AlertRepository alertRepository,
                          FollowUpService followUpService) {
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
        Patient saved = patientRepository.save(PatientMapper.INSTANCE.patientDTOToEntity(patientDTO));
        log.info("Created patient with ID {}", saved.getId());
        return PatientMapper.INSTANCE.patientToDTO(saved);
    }

    @Transactional
    public void deletePatientById(Long id) throws PatientNotFoundException {
        if (!patientRepository.existsById(id)) {
            throw new PatientNotFoundException();
        }
        if (followUpRepository.existsByPatientId(id) || appointmentRepository.existsByPatientId(id)) {
            throw ApiException.conflict("PATIENT_HAS_RECORDS", "Patient has follow-ups or appointments and cannot be deleted");
        }
        patientRepository.deleteById(id);
        log.info("Deleted patient with ID {}", id);
    }
}
