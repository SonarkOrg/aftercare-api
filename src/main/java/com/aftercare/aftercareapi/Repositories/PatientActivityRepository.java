package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface PatientActivityRepository extends JpaRepository<PatientActivity, Long> {

    Slice<PatientActivity> findByPatientIdOrderByOccurredAtDescIdDesc(Long patientId, Pageable pageable);

    Slice<PatientActivity> findByPatientIdAndCategoryInOrderByOccurredAtDescIdDesc(Long patientId,
                                                                                    Collection<TimelineCategory> categories,
                                                                                    Pageable pageable);

    @Modifying
    @Query("delete from PatientActivity a where a.patientId = :patientId")
    int deleteByPatientId(@Param("patientId") Long patientId);
}
