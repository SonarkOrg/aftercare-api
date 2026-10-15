package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AccessLinkKind;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Appointment;
import com.aftercare.aftercareapi.Models.AppointmentIntake;
import com.aftercare.aftercareapi.Models.CheckIn;
import com.aftercare.aftercareapi.Models.Dto.PublicSessionDTO;
import com.aftercare.aftercareapi.Models.Dto.PublicSubmitResultDTO;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Models.PatientAccessLink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Patient-facing questionnaire flow behind /api/public/q/{code}. Never logs codes or request paths. */
@Service
public class PublicQuestionnaireService {

    private final AccessLinkService accessLinkService;
    private final CheckInService checkInService;
    private final AppointmentService appointmentService;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PublicQuestionnaireService(AccessLinkService accessLinkService, CheckInService checkInService,
                                      AppointmentService appointmentService, ObjectMapper mapper, Clock clock) {
        this.accessLinkService = accessLinkService;
        this.checkInService = checkInService;
        this.appointmentService = appointmentService;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicSessionDTO get(String code) {
        PatientAccessLink link = usableLink(code);
        if (link.getKind() == AccessLinkKind.FOLLOW_UP) {
            FollowUp f = link.getFollowUp();
            return new PublicSessionDTO("FOLLOW_UP_CHECKIN", checkInService.existsToday(f),
                    questionnaire(f.getQuestionnaire().getFhirJson()),
                    new PublicSessionDTO.CheckInInfo(LocalDate.now(clock), f.getEndDate()), null);
        }
        Appointment a = link.getAppointment();
        return new PublicSessionDTO("APPOINTMENT_INTAKE", appointmentService.hasIntake(a.getId()),
                questionnaire(a.getQuestionnaire().getFhirJson()), null,
                new PublicSessionDTO.AppointmentInfo(a.getType(),
                        LocalDateTime.ofInstant(a.getScheduledAt(), clock.getZone()), a.getDurationMinutes(), a.getLocation()));
    }

    @Transactional
    public PublicSubmitResultDTO submit(String code, JsonNode body) {
        PatientAccessLink link = usableLink(code);
        if (link.getKind() == AccessLinkKind.FOLLOW_UP) {
            CheckIn c = checkInService.submit(link.getFollowUp(), body);
            return new PublicSubmitResultDTO("FOLLOW_UP_CHECKIN", c.getSubmittedAt(), c.getCheckInDate());
        }
        AppointmentIntake i = appointmentService.submitIntake(link.getAppointment(), body);
        return new PublicSubmitResultDTO("APPOINTMENT_INTAKE", i.getSubmittedAt(), null);
    }

    private PatientAccessLink usableLink(String code) {
        return accessLinkService.usableLink(code);
    }

    private JsonNode questionnaire(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored questionnaire is not valid JSON", e);
        }
    }
}
