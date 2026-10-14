package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Models.Patient;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<Patient, Long> {
}
