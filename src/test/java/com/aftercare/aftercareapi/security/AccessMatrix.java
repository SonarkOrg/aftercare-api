package com.aftercare.aftercareapi.security;

import com.aftercare.aftercareapi.Enums.AccessRole;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.aftercare.aftercareapi.Enums.AccessRole.*;

/**
 * The permission matrix of the spec, written out by hand and independent of AccessPolicy / RolePermissions, so a change to
 * the policy shows up as failing tests. One entry per rule (the four medical lists expanded), with the roles allowed.
 */
final class AccessMatrix {

    record Row(HttpMethod method, String pattern, String path, Set<AccessRole> allowed) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    static final Set<AccessRole> ALL = EnumSet.of(ADMIN, PHYSICIAN, NURSE, RECEPTION);
    static final Set<AccessRole> CLINICAL = EnumSet.of(ADMIN, PHYSICIAN, NURSE);
    static final Set<AccessRole> ADMIN_ONLY = EnumSet.of(ADMIN);

    static final List<Row> ROWS = new ArrayList<>();

    private static void add(HttpMethod m, String pattern, Set<AccessRole> allowed) {
        ROWS.add(new Row(m, pattern, pattern.replace("*", "999999"), allowed));
    }

    static {
        add(HttpMethod.GET, "/api/me", ALL);
        add(HttpMethod.PUT, "/api/me/preferences", ALL);
        add(HttpMethod.POST, "/api/patients/register", ALL);
        add(HttpMethod.GET, "/api/patients", ALL);
        add(HttpMethod.GET, "/api/patients/*", ALL);
        add(HttpMethod.PUT, "/api/patients/*", ALL);
        add(HttpMethod.DELETE, "/api/patients/*", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/patients/*/medical", CLINICAL);
        add(HttpMethod.PUT, "/api/patients/*/health-number", CLINICAL);
        add(HttpMethod.DELETE, "/api/patients/*/health-number", CLINICAL);
        add(HttpMethod.POST, "/api/patients/*/health-number/reveal", CLINICAL);
        for (String item : List.of("allergies", "medications", "procedures", "diagnoses")) {
            add(HttpMethod.POST, "/api/patients/*/" + item, CLINICAL);
        }
        for (String item : List.of("allergies", "medications", "procedures", "diagnoses")) {
            add(HttpMethod.PUT, "/api/patients/*/" + item + "/*", CLINICAL);
        }
        for (String item : List.of("allergies", "medications", "procedures", "diagnoses")) {
            add(HttpMethod.DELETE, "/api/patients/*/" + item + "/*", CLINICAL);
        }
        add(HttpMethod.GET, "/api/patients/*/emergency-contacts", ALL);
        add(HttpMethod.POST, "/api/patients/*/emergency-contacts", ALL);
        add(HttpMethod.PUT, "/api/patients/*/emergency-contacts/*", ALL);
        add(HttpMethod.DELETE, "/api/patients/*/emergency-contacts/*", ALL);
        add(HttpMethod.GET, "/api/patients/*/care-team", ALL);
        add(HttpMethod.POST, "/api/patients/*/care-team", CLINICAL);
        add(HttpMethod.DELETE, "/api/patients/*/care-team/*", CLINICAL);
        add(HttpMethod.GET, "/api/patients/*/contact-preferences", ALL);
        add(HttpMethod.PUT, "/api/patients/*/contact-preferences", ALL);
        add(HttpMethod.GET, "/api/patients/*/notifications", ALL);
        add(HttpMethod.POST, "/api/patients/*/follow-ups", CLINICAL);
        add(HttpMethod.GET, "/api/patients/*/follow-ups", CLINICAL);
        add(HttpMethod.POST, "/api/follow-ups/*/access-code", CLINICAL);
        add(HttpMethod.POST, "/api/follow-ups/*/close", CLINICAL);
        add(HttpMethod.POST, "/api/follow-ups/*/send-link", CLINICAL);
        add(HttpMethod.GET, "/api/patients/*/check-ins", CLINICAL);
        add(HttpMethod.GET, "/api/check-ins", CLINICAL);
        add(HttpMethod.GET, "/api/alerts", CLINICAL);
        add(HttpMethod.GET, "/api/patients/*/alerts", CLINICAL);
        add(HttpMethod.POST, "/api/alerts/*/acknowledge", CLINICAL);
        add(HttpMethod.POST, "/api/alerts/*/resolve", CLINICAL);
        add(HttpMethod.POST, "/api/appointments", ALL);
        add(HttpMethod.GET, "/api/appointments", ALL);
        add(HttpMethod.GET, "/api/appointments/metrics", ALL);
        add(HttpMethod.GET, "/api/appointments/*", ALL);
        add(HttpMethod.PUT, "/api/appointments/*", ALL);
        add(HttpMethod.DELETE, "/api/appointments/*", ALL);
        add(HttpMethod.POST, "/api/appointments/*/access-code", ALL);
        add(HttpMethod.POST, "/api/appointments/*/review", CLINICAL);
        add(HttpMethod.POST, "/api/appointments/*/status", ALL);
        add(HttpMethod.POST, "/api/appointments/*/send-link", ALL);
        add(HttpMethod.GET, "/api/patients/*/appointments", ALL);
        add(HttpMethod.GET, "/api/questionnaires", ALL);
        add(HttpMethod.GET, "/api/questionnaires/*", ALL);
        add(HttpMethod.GET, "/api/patients/*/timeline", CLINICAL);
        add(HttpMethod.POST, "/api/patients/*/notes", CLINICAL);
        add(HttpMethod.PUT, "/api/notes/*", CLINICAL);
        add(HttpMethod.POST, "/api/notes/*/delete", CLINICAL);
        add(HttpMethod.GET, "/api/patients/*/documents", CLINICAL);
        add(HttpMethod.POST, "/api/patients/*/documents", CLINICAL);
        add(HttpMethod.GET, "/api/documents/*/content", CLINICAL);
        add(HttpMethod.POST, "/api/documents/*/delete", CLINICAL);
        add(HttpMethod.GET, "/api/messages/inbox", CLINICAL);
        add(HttpMethod.GET, "/api/messages/unread-count", CLINICAL);
        add(HttpMethod.GET, "/api/patients/*/messages", CLINICAL);
        add(HttpMethod.POST, "/api/patients/*/messages", CLINICAL);
        add(HttpMethod.POST, "/api/patients/*/messages/read", CLINICAL);
        add(HttpMethod.GET, "/api/patients/*/audit", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/audit", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/settings/clinic", ALL);
        add(HttpMethod.PUT, "/api/settings/clinic", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/settings/notifications", ADMIN_ONLY);
        add(HttpMethod.PUT, "/api/settings/notifications", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/settings/retention", ADMIN_ONLY);
        add(HttpMethod.PUT, "/api/settings/retention", ADMIN_ONLY);
        add(HttpMethod.POST, "/api/retention/dry-run", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/retention/runs", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/staff", ALL);
        add(HttpMethod.POST, "/api/staff", ADMIN_ONLY);
        add(HttpMethod.PUT, "/api/staff/*", ADMIN_ONLY);
        add(HttpMethod.POST, "/api/staff/*/deactivate", ADMIN_ONLY);
        add(HttpMethod.POST, "/api/staff/*/reactivate", ADMIN_ONLY);
        add(HttpMethod.GET, "/api/reports/summary", CLINICAL);
    }

    private AccessMatrix() {
    }
}
