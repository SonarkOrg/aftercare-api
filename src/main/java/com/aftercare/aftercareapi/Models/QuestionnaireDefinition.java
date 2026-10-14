package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "questionnaire_definitions",
        uniqueConstraints = @UniqueConstraint(name = "uk_qdef_url_version", columnNames = {"url", "version"}))
public class QuestionnaireDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 255)
    private String url;
    @Column(nullable = false, length = 32)
    private String version;
    @Column(nullable = false, length = 255)
    private String titleFr;
    @Column(nullable = false, length = 255)
    private String titleEn;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private QuestionnairePurpose purpose;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Specialty specialty;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "questionnaire_definition_appointment_types",
            joinColumns = @JoinColumn(name = "definition_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "appointment_type", length = 32)
    @Builder.Default
    private Set<AppointmentType> appointmentTypes = new HashSet<>();
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private QuestionnaireStatus status;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String fhirJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String rulesJson;
    @Column(nullable = false, length = 64)
    private String contentHash;
    @Column(nullable = false)
    private Instant loadedAt;
}
