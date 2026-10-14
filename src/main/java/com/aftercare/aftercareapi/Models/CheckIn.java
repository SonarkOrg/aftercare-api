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
@Table(name = "check_ins",
        uniqueConstraints = @UniqueConstraint(name = "uk_check_ins_follow_up_date", columnNames = {"follow_up_id", "check_in_date"}))
public class CheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follow_up_id", nullable = false)
    private FollowUp followUp;
    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;
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
}
