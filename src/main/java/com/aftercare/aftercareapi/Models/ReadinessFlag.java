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
@Embeddable
public class ReadinessFlag {

    @Column(nullable = false, length = 64)
    private String linkId;
    @Column(nullable = false, length = 500)
    private String messageEn;
    @Column(nullable = false, length = 500)
    private String messageFr;
}
