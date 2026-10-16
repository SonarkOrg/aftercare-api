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
@Table(name = "staff_members",
        indexes = @Index(name = "idx_staff_active", columnList = "active"),
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_staff_email", columnNames = "email"),
                @UniqueConstraint(name = "uk_staff_auth0_subject", columnNames = "auth0Subject")})
public class StaffMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String displayName;
    @Column(length = 255)
    private String email;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StaffRole role;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccessRole accessRole;
    @Column(length = 255)
    private String auth0Subject;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 255)
    private String createdBySub;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false, length = 255)
    private String updatedBySub;
    @Version
    private Long version;
}
