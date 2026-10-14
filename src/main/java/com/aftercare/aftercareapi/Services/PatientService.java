package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Mappers.PatientMapper;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.PatientDTO;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);

    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Transactional(readOnly = true)
    public List<PatientDTO> getPatients() {
        return patientRepository.findAll()
                .stream()
                .map(PatientMapper.INSTANCE::patientToDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public PatientDTO getPatientById(Long id) throws PatientNotFoundException {
        return patientRepository.findById(id)
                .map(PatientMapper.INSTANCE::patientToDTO)
                .orElseThrow(() -> new PatientNotFoundException("Patient with ID " + id + " not found"));
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
            throw new PatientNotFoundException("Patient with ID " + id + " not found");
        }
        patientRepository.deleteById(id);
        log.info("Deleted patient with ID {}", id);
    }
}
