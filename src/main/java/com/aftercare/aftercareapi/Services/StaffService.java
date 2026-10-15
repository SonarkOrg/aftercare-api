package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Models.Dto.StaffDTO;
import com.aftercare.aftercareapi.Models.Dto.StaffRequest;
import com.aftercare.aftercareapi.Models.StaffMember;
import com.aftercare.aftercareapi.Repositories.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** The clinic staff directory. Deactivating a member keeps their care-team assignments and history. */
@Service
public class StaffService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern SUBJECT = Pattern.compile("^[^\\s]+$");

    private final StaffRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public StaffService(StaffRepository repository, AuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StaffDTO> list(boolean includeInactive) {
        List<StaffMember> all = includeInactive ? repository.findAll() : repository.findByActiveTrue();
        return all.stream()
                .sorted(Comparator.comparing(StaffMember::getDisplayName, Texts.collation()).thenComparing(StaffMember::getId))
                .map(StaffService::toDto).toList();
    }

    @Transactional
    public StaffDTO create(StaffRequest r, Actor actor) {
        Clean c = validate(r);
        checkDuplicates(c, null);
        Instant now = Instant.now(clock);
        StaffMember s = new StaffMember();
        s.setActive(true);
        s.setCreatedAt(now);
        s.setCreatedBySub(actor.sub());
        apply(s, c, r, now, actor);
        s = repository.saveAndFlush(s);
        audit.recordChange(actor, AuditAction.STAFF_CREATED, null, AuditResource.STAFF, s.getId(), null);
        return toDto(s);
    }

    @Transactional
    public StaffDTO update(Long id, StaffRequest r, Actor actor) {
        StaffMember s = find(id);
        Clean c = validate(r);
        checkDuplicates(c, id);
        List<String> changed = new ArrayList<>();
        if (!s.getDisplayName().equals(c.displayName)) changed.add("displayName");
        if (!Objects.equals(s.getEmail(), c.email)) changed.add("email");
        if (s.getRole() != r.role()) changed.add("role");
        if (!Objects.equals(s.getAuth0Subject(), c.subject)) changed.add("auth0Subject");
        apply(s, c, r, Instant.now(clock), actor);
        s = repository.saveAndFlush(s);
        audit.recordChange(actor, AuditAction.STAFF_UPDATED, null, AuditResource.STAFF, id,
                changed.isEmpty() ? null : String.join(",", changed));
        return toDto(s);
    }

    @Transactional
    public StaffDTO deactivate(Long id, Actor actor) {
        StaffMember s = find(id);
        if (!s.isActive()) {
            throw ApiException.invalidState("Staff member is already inactive");
        }
        return setActive(s, false, AuditAction.STAFF_DEACTIVATED, actor);
    }

    @Transactional
    public StaffDTO reactivate(Long id, Actor actor) {
        StaffMember s = find(id);
        if (s.isActive()) {
            throw ApiException.invalidState("Staff member is already active");
        }
        return setActive(s, true, AuditAction.STAFF_REACTIVATED, actor);
    }

    private StaffDTO setActive(StaffMember s, boolean active, AuditAction action, Actor actor) {
        s.setActive(active);
        s.setUpdatedAt(Instant.now(clock));
        s.setUpdatedBySub(actor.sub());
        s = repository.saveAndFlush(s);
        audit.recordChange(actor, action, null, AuditResource.STAFF, s.getId(), null);
        return toDto(s);
    }

    private record Clean(String displayName, String email, String subject) {
    }

    private Clean validate(StaffRequest r) {
        Fields f = new Fields();
        String name = f.required("displayName", r.displayName(), 120);
        String email = f.optional("email", r.email(), 255);
        if (email != null) {
            if (EMAIL.matcher(email).matches()) {
                email = email.toLowerCase(Locale.ROOT);
            } else {
                f.error("email", "must be a well-formed email address");
            }
        }
        String subject = f.optional("auth0Subject", r.auth0Subject(), 255);
        if (subject != null && !SUBJECT.matcher(subject).matches()) {
            f.error("auth0Subject", "must not contain whitespace");
        }
        f.check();
        return new Clean(name, email, subject);
    }

    private void checkDuplicates(Clean c, Long selfId) {
        if (c.email != null && repository.findByEmailIgnoreCase(c.email).filter(o -> !o.getId().equals(selfId)).isPresent()
                || c.subject != null && repository.findByAuth0Subject(c.subject).filter(o -> !o.getId().equals(selfId)).isPresent()) {
            throw ApiException.conflict("DUPLICATE_STAFF", "A staff member with this email or login already exists");
        }
    }

    private void apply(StaffMember s, Clean c, StaffRequest r, Instant now, Actor actor) {
        s.setDisplayName(c.displayName);
        s.setEmail(c.email);
        s.setRole(r.role());
        s.setAuth0Subject(c.subject);
        s.setUpdatedAt(now);
        s.setUpdatedBySub(actor.sub());
    }

    private StaffMember find(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Not found"));
    }

    static StaffDTO toDto(StaffMember s) {
        return new StaffDTO(s.getId(), s.getDisplayName(), s.getEmail(), s.getRole(), s.getAuth0Subject(), s.isActive(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
