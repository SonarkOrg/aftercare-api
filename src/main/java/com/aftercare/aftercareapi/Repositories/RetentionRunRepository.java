package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Models.RetentionRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetentionRunRepository extends JpaRepository<RetentionRun, Long> {

    List<RetentionRun> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);
}
