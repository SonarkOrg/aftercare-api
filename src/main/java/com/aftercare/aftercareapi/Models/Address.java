package com.aftercare.aftercareapi.Models;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Embeddable
public class Address {
    @NotBlank
    @Size(max = 255)
    private String city;
    @NotBlank
    @Size(max = 255)
    private String state;
    @NotBlank
    @Size(max = 255)
    private String zip;
}
