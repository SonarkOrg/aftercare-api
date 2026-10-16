package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AlertSeverity;
import com.aftercare.aftercareapi.Models.Alert;
import com.aftercare.aftercareapi.Models.CheckIn;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Questionnaires.QuestionnaireRegistry;
import com.aftercare.aftercareapi.Questionnaires.RuleHit;
import com.aftercare.aftercareapi.Questionnaires.RuleOutcome;
import com.aftercare.aftercareapi.Repositories.AlertRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlertServiceCreatedAtTests {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final AlertRepository alerts = mock(AlertRepository.class);
    private final ActivityService activity = mock(ActivityService.class);
    private final AlertService service = new AlertService(alerts, mock(PatientRepository.class), mock(QuestionnaireRegistry.class),
            activity, mock(ActorResolver.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private CheckIn checkIn(Instant submittedAt) {
        Patient p = new Patient();
        p.setId(7L);
        FollowUp f = new FollowUp();
        f.setPatient(p);
        CheckIn c = new CheckIn();
        c.setFollowUp(f);
        c.setSubmittedAt(submittedAt);
        return c;
    }

    private Alert created(CheckIn c) {
        when(alerts.save(any(Alert.class))).thenAnswer(i -> i.getArgument(0));
        service.createAlerts(c, List.of(new RuleHit("PAIN", RuleOutcome.ALERT, AlertSeverity.HIGH, "en", "fr", "9")));
        ArgumentCaptor<Alert> cap = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void alertIsStampedWithTheCheckInSubmissionTimeNotTheClock() {
        Instant submitted = NOW.minusSeconds(86_400);
        assertEquals(submitted, created(checkIn(submitted)).getCreatedAt());
        verify(activity).record(eq(7L), any(), eq(submitted), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void fallsBackToTheClockWhenSubmissionTimeIsMissing() {
        assertEquals(NOW, created(checkIn(null)).getCreatedAt());
    }
}
