package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "patient_messages", indexes = {
        @Index(name = "idx_messages_patient_id", columnList = "patient_id, id"),
        @Index(name = "idx_messages_unread_staff", columnList = "sender, readByStaffAt"),
        @Index(name = "idx_messages_rate", columnList = "patient_id, sender, createdAt")})
public class PatientMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageSender sender;
    @Column(nullable = false, length = 2000)
    private String body;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(length = 255)
    private String staffSub;
    @Column(length = 255)
    private String staffName;
    private Long accessLinkId;
    private Instant readByStaffAt;
    @Column(length = 255)
    private String readByStaffSub;
    private Instant readByPatientAt;
}
