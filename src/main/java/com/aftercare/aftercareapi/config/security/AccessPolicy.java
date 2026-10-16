package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.Enums.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.aftercare.aftercareapi.Enums.Permission.*;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

/**
 * The whole endpoint to permission matrix, in one place. Rules are applied in order; anything under /api/** that is not
 * listed is denied (see SecurityConfig). {@code *} matches exactly one path segment. A rule with a null permission only
 * needs an authenticated JWT (used by /api/me alone).
 */
public final class AccessPolicy {

    public record Rule(HttpMethod method, String pattern, Permission permission) {
    }

    public static final List<Rule> RULES;
    private static final List<RequestMatcher> MATCHERS;

    static {
        List<Rule> r = new ArrayList<>();
        r.add(new Rule(GET, "/api/me", null));

        r.add(new Rule(POST, "/api/patients/register", PATIENT_WRITE));
        r.add(new Rule(GET, "/api/patients", PATIENT_READ));
        r.add(new Rule(GET, "/api/patients/*", PATIENT_READ));
        r.add(new Rule(PUT, "/api/patients/*", PATIENT_WRITE));
        r.add(new Rule(DELETE, "/api/patients/*", PATIENT_DELETE));
        r.add(new Rule(GET, "/api/patients/*/medical", CLINICAL_READ));
        r.add(new Rule(PUT, "/api/patients/*/health-number", CLINICAL_WRITE));
        r.add(new Rule(DELETE, "/api/patients/*/health-number", CLINICAL_WRITE));
        r.add(new Rule(POST, "/api/patients/*/health-number/reveal", HEALTH_NUMBER_REVEAL));
        for (String item : List.of("allergies", "medications", "procedures", "diagnoses")) {
            r.add(new Rule(POST, "/api/patients/*/" + item, CLINICAL_WRITE));
        }
        for (String item : List.of("allergies", "medications", "procedures", "diagnoses")) {
            r.add(new Rule(PUT, "/api/patients/*/" + item + "/*", CLINICAL_WRITE));
        }
        for (String item : List.of("allergies", "medications", "procedures", "diagnoses")) {
            r.add(new Rule(DELETE, "/api/patients/*/" + item + "/*", CLINICAL_WRITE));
        }
        r.add(new Rule(GET, "/api/patients/*/emergency-contacts", PATIENT_READ));
        r.add(new Rule(POST, "/api/patients/*/emergency-contacts", PATIENT_WRITE));
        r.add(new Rule(PUT, "/api/patients/*/emergency-contacts/*", PATIENT_WRITE));
        r.add(new Rule(DELETE, "/api/patients/*/emergency-contacts/*", PATIENT_WRITE));
        r.add(new Rule(GET, "/api/patients/*/care-team", PATIENT_READ));
        r.add(new Rule(POST, "/api/patients/*/care-team", CLINICAL_WRITE));
        r.add(new Rule(DELETE, "/api/patients/*/care-team/*", CLINICAL_WRITE));
        r.add(new Rule(GET, "/api/patients/*/contact-preferences", PATIENT_READ));
        r.add(new Rule(PUT, "/api/patients/*/contact-preferences", PATIENT_WRITE));
        r.add(new Rule(GET, "/api/patients/*/notifications", PATIENT_READ));

        r.add(new Rule(POST, "/api/patients/*/follow-ups", CLINICAL_WRITE));
        r.add(new Rule(GET, "/api/patients/*/follow-ups", CLINICAL_READ));
        r.add(new Rule(POST, "/api/follow-ups/*/access-code", CLINICAL_WRITE));
        r.add(new Rule(POST, "/api/follow-ups/*/close", CLINICAL_WRITE));
        r.add(new Rule(POST, "/api/follow-ups/*/send-link", CLINICAL_WRITE));
        r.add(new Rule(GET, "/api/patients/*/check-ins", CLINICAL_READ));
        r.add(new Rule(GET, "/api/check-ins", CLINICAL_READ));

        r.add(new Rule(GET, "/api/alerts", CLINICAL_READ));
        r.add(new Rule(GET, "/api/patients/*/alerts", CLINICAL_READ));
        r.add(new Rule(POST, "/api/alerts/*/acknowledge", CLINICAL_WRITE));
        r.add(new Rule(POST, "/api/alerts/*/resolve", CLINICAL_WRITE));

        r.add(new Rule(POST, "/api/appointments", APPOINTMENT_WRITE));
        r.add(new Rule(GET, "/api/appointments", APPOINTMENT_READ));
        r.add(new Rule(GET, "/api/appointments/metrics", APPOINTMENT_READ));
        r.add(new Rule(GET, "/api/appointments/*", APPOINTMENT_READ));
        r.add(new Rule(PUT, "/api/appointments/*", APPOINTMENT_WRITE));
        r.add(new Rule(DELETE, "/api/appointments/*", APPOINTMENT_WRITE));
        r.add(new Rule(POST, "/api/appointments/*/access-code", APPOINTMENT_WRITE));
        r.add(new Rule(POST, "/api/appointments/*/review", CLINICAL_WRITE));
        r.add(new Rule(POST, "/api/appointments/*/status", APPOINTMENT_WRITE));
        r.add(new Rule(POST, "/api/appointments/*/send-link", APPOINTMENT_WRITE));
        r.add(new Rule(GET, "/api/patients/*/appointments", APPOINTMENT_READ));

        r.add(new Rule(GET, "/api/questionnaires", FORMS_READ));
        r.add(new Rule(GET, "/api/questionnaires/*", FORMS_READ));

        r.add(new Rule(GET, "/api/patients/*/timeline", CLINICAL_READ));
        r.add(new Rule(POST, "/api/patients/*/notes", CLINICAL_WRITE));
        r.add(new Rule(PUT, "/api/notes/*", CLINICAL_WRITE));
        r.add(new Rule(POST, "/api/notes/*/delete", CLINICAL_WRITE));

        r.add(new Rule(GET, "/api/patients/*/documents", CLINICAL_READ));
        r.add(new Rule(POST, "/api/patients/*/documents", CLINICAL_WRITE));
        r.add(new Rule(GET, "/api/documents/*/content", CLINICAL_READ));
        r.add(new Rule(POST, "/api/documents/*/delete", CLINICAL_WRITE));

        r.add(new Rule(GET, "/api/messages/inbox", CLINICAL_READ));
        r.add(new Rule(GET, "/api/messages/unread-count", CLINICAL_READ));
        r.add(new Rule(GET, "/api/patients/*/messages", CLINICAL_READ));
        r.add(new Rule(POST, "/api/patients/*/messages", CLINICAL_WRITE));
        r.add(new Rule(POST, "/api/patients/*/messages/read", CLINICAL_WRITE));

        r.add(new Rule(GET, "/api/patients/*/audit", AUDIT_READ));
        r.add(new Rule(GET, "/api/audit", AUDIT_READ));

        r.add(new Rule(GET, "/api/settings/clinic", SETTINGS_READ));
        r.add(new Rule(PUT, "/api/settings/clinic", SETTINGS_MANAGE));
        r.add(new Rule(GET, "/api/settings/notifications", SETTINGS_MANAGE));
        r.add(new Rule(PUT, "/api/settings/notifications", SETTINGS_MANAGE));
        r.add(new Rule(GET, "/api/settings/retention", SETTINGS_MANAGE));
        r.add(new Rule(PUT, "/api/settings/retention", SETTINGS_MANAGE));
        r.add(new Rule(POST, "/api/retention/dry-run", SETTINGS_MANAGE));
        r.add(new Rule(GET, "/api/retention/runs", SETTINGS_MANAGE));

        r.add(new Rule(GET, "/api/staff", STAFF_READ));
        r.add(new Rule(POST, "/api/staff", STAFF_MANAGE));
        r.add(new Rule(PUT, "/api/staff/*", STAFF_MANAGE));
        r.add(new Rule(POST, "/api/staff/*/deactivate", STAFF_MANAGE));
        r.add(new Rule(POST, "/api/staff/*/reactivate", STAFF_MANAGE));

        r.add(new Rule(GET, "/api/reports/summary", REPORTS_READ));

        RULES = List.copyOf(r);
        MATCHERS = RULES.stream().map(AccessPolicy::matcher).toList();
    }

    private AccessPolicy() {
    }

    public static RequestMatcher matcher(Rule rule) {
        return AntPathRequestMatcher.antMatcher(rule.method(), rule.pattern());
    }

    /** The first rule matching the request, in table order. */
    public static Optional<Rule> firstMatch(HttpServletRequest request) {
        for (int i = 0; i < RULES.size(); i++) {
            if (MATCHERS.get(i).matches(request)) {
                return Optional.of(RULES.get(i));
            }
        }
        return Optional.empty();
    }
}
