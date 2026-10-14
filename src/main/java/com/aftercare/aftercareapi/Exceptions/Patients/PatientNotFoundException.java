package com.aftercare.aftercareapi.Exceptions.Patients;

import com.aftercare.aftercareapi.Exceptions.ApiException;
import org.springframework.http.HttpStatus;

public class PatientNotFoundException extends ApiException {
    public PatientNotFoundException() {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", "Patient not found");
    }
}
