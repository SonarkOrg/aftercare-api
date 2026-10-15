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
@Table(name = "clinic_settings")
public class ClinicSettings {

    public static final long ID = 1L;

    @Id
    private Long id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(length = 200)
    private String addressLine;
    @Column(length = 100)
    private String city;
    @Column(nullable = false, length = 2)
    private String province;
    @Column(length = 7)
    private String postalCode;
    @Column(length = 25)
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Language defaultLanguage;
    @Column(nullable = false)
    private boolean notifyHigh;
    @Column(nullable = false)
    private boolean notifyMedium;
    private Instant updatedAt;
    @Column(length = 255)
    private String updatedBySub;
    @Column(length = 255)
    private String updatedByName;
    @Version
    private Long version;
}
