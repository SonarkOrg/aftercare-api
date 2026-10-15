package com.aftercare.aftercareapi.Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

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
}
