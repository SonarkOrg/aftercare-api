package com.aftercare.aftercareapi.Repositories;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

public interface QuestionnaireDefinitionRepository extends JpaRepository<QuestionnaireDefinition, Long> {
    Optional<QuestionnaireDefinition> findByUrlAndVersion(String url, String version);
}
