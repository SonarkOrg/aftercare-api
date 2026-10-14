package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public interface AppointmentIntakeRepository extends JpaRepository<AppointmentIntake, Long> {
    Optional<AppointmentIntake> findByAppointmentId(Long appointmentId);

    boolean existsByAppointmentId(Long appointmentId);

    List<AppointmentIntake> findByAppointmentIdIn(Collection<Long> appointmentIds);
}
