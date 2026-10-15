package com.aftercare.aftercareapi.Models.Dto;

import com.aftercare.aftercareapi.Enums.*;

import java.time.Instant;

public record NoteCreateRequest(String body, Instant notedAt) {
}
