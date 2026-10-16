package com.aftercare.aftercareapi.config.security;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Exceptions.ApiError;
import com.aftercare.aftercareapi.Services.Actor;
import com.aftercare.aftercareapi.Services.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 403 for an authenticated caller: NOT_STAFF when the login has no active staff member (not audited), otherwise
 * FORBIDDEN, which is audited as ACCESS_DENIED with the method and the matching rule pattern.
 */
@Component
public class StaffAccessDeniedHandler implements AccessDeniedHandler {

    public static final String NOT_STAFF_MESSAGE = "Your login is not linked to an active staff member";
    public static final String FORBIDDEN_MESSAGE = "You do not have permission for this action";
    private static final Logger log = LoggerFactory.getLogger(StaffAccessDeniedHandler.class);

    /** At most one audit row per identical (subject, method, path) in this window, so a loop cannot flood the log. */
    static final Duration THROTTLE = Duration.ofMinutes(1);
    static final int MAX_TRACKED = 5000;

    private final ObjectMapper mapper;
    private final AuditService audit;
    private final Clock clock;
    private final Map<String, Instant> recent = new LinkedHashMap<>();

    public StaffAccessDeniedHandler(ObjectMapper mapper, AuditService audit, Clock clock) {
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
    }

    /** True when this denial should be audited now; remembers it (bounded, oldest evicted first). */
    private synchronized boolean shouldAudit(String key) {
        Instant now = Instant.now(clock);
        Instant last = recent.remove(key);
        if (last != null && now.isBefore(last.plus(THROTTLE)) && !now.isBefore(last)) {
            recent.put(key, last); // keep the original time: the window does not slide
            return false;
        }
        recent.put(key, now);
        var it = recent.entrySet().iterator();
        while (recent.size() > MAX_TRACKED && it.hasNext()) {
            it.next();
            it.remove();
        }
        return true;
    }

    /** Forgets all throttle state (tests share one application context and a frozen clock). */
    public synchronized void resetThrottle() {
        recent.clear();
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean staff = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> CurrentStaff.STAFF_AUTHORITY.equals(a.getAuthority()));
        if (!staff) {
            write(response, new ApiError(403, "NOT_STAFF", NOT_STAFF_MESSAGE, Map.of()));
            return;
        }
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String key = auth.getName() + "|" + request.getMethod() + " " + (path.length() > 200 ? path.substring(0, 200) : path);
            if (shouldAudit(key)) {
                audit.recordAccess(actor(request, auth), AuditAction.ACCESS_DENIED, patientId(request), AuditResource.ENDPOINT,
                        null, request.getMethod() + " " + AccessPolicy.firstMatch(request).map(AccessPolicy.Rule::pattern).orElse("/api/**"));
            }
        } catch (RuntimeException e) {
            log.warn("Could not record ACCESS_DENIED ({})", e.getClass().getSimpleName());
        }
        write(response, new ApiError(403, "FORBIDDEN", FORBIDDEN_MESSAGE, Map.of()));
    }

    private static Actor actor(HttpServletRequest request, Authentication auth) {
        Object p = request.getAttribute(StaffPrincipal.ATTRIBUTE);
        if (p instanceof StaffPrincipal sp) {
            return new Actor(sp.sub(), sp.displayName());
        }
        return new Actor(auth.getName(), null);
    }

    /** The patient id of /api/patients/{id}/..., else null. */
    private static Long patientId(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String[] parts = path.split("/");
        // "", "api", "patients", "{id}", ...
        if (parts.length > 3 && "api".equals(parts[1]) && "patients".equals(parts[2]) && parts[3].matches("\\d{1,18}")) {
            return Long.parseLong(parts[3]);
        }
        return null;
    }

    private void write(HttpServletResponse response, ApiError body) throws IOException {
        response.setStatus(body.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        mapper.writeValue(response.getOutputStream(), body);
    }
}
