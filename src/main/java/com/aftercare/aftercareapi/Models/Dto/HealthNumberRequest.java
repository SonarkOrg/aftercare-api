package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;


public record HealthNumberRequest(String healthNumber) {
    @Override
    public String toString() {
        return "HealthNumberRequest[***]";
    }
}
