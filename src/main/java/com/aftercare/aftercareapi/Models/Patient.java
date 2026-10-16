package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.ContactConsent;
import com.aftercare.aftercareapi.Enums.Language;
import com.aftercare.aftercareapi.Enums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "patients")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String gender;
    private Address address;
    private LocalDate dateOfBirth;
    /** AES-GCM ciphertext of the RAMQ number ("v1:..."). Never logged, never serialized. */
    @Column(length = 512)
    private String healthNumberEnc;
    /** "TREM •••• ••12". */
    @Column(length = 20)
    private String healthNumberMasked;
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NotificationChannel preferredChannel = NotificationChannel.EMAIL;
    /** Null means the clinic default language. */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Language preferredLanguage;
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContactConsent contactConsent = ContactConsent.UNKNOWN;
    private Instant contactConsentUpdatedAt;
    @Column(length = 255)
    private String contactConsentUpdatedBySub;
    @Column(length = 255)
    private String contactConsentUpdatedByName;
}
