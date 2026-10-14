package com.aftercare.aftercareapi.Models;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientDTO {

    private Long id;
    @NotBlank
    @Size(max = 255)
    private String firstName;
    @NotBlank
    @Size(max = 255)
    private String lastName;
    @NotBlank
    @Email
    @Size(max = 255)
    private String email;
    @NotBlank
    @Size(max = 255)
    private String phone;
    @NotBlank
    @Size(max = 255)
    private String gender;
    @NotNull
    @Valid
    private Address address;

    //TODO Move to utilityClass
    public void convertGender(String gender){
        if(gender.equalsIgnoreCase("m")){
            this.gender = "male";
        }

        if(gender.equalsIgnoreCase("f")){
            this.gender = "female";
        }
    }
}
