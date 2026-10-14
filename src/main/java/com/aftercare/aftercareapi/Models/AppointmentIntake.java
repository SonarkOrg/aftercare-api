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
@Table(name = "appointment_intakes")
public class AppointmentIntake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false, unique = true)
    private Appointment appointment;
    @Column(nullable = false)
    private Instant submittedAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "questionnaire_id", nullable = false)
    private QuestionnaireDefinition questionnaire;
    @Column(nullable = false, length = 255)
    private String questionnaireUrl;
    @Column(nullable = false, length = 32)
    private String questionnaireVersion;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String responseJson;
    @ElementCollection
    @CollectionTable(name = "appointment_intake_flags", joinColumns = @JoinColumn(name = "intake_id"))
    @OrderColumn(name = "position")
    @Builder.Default
    private List<ReadinessFlag> flags = new ArrayList<>();
}
