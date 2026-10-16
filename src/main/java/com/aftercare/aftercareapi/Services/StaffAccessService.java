package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AccessRole;
import com.aftercare.aftercareapi.Enums.AuditAction;
import com.aftercare.aftercareapi.Enums.AuditResource;
import com.aftercare.aftercareapi.Enums.StaffRole;
import com.aftercare.aftercareapi.Models.StaffMember;
import com.aftercare.aftercareapi.Repositories.StaffRepository;
import com.aftercare.aftercareapi.config.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps a JWT to an active staff member on every request (one indexed query). Roles come only from the directory.
 * Also performs the one-time administrator bootstrap (BOOTSTRAP_ADMIN_SUB).
 */
@Service
public class StaffAccessService {

    private static final Logger log = LoggerFactory.getLogger(StaffAccessService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StaffRepository repository;
    private final AuditService audit;
    private final Clock clock;
    private final String bootstrapSub;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public StaffAccessService(StaffRepository repository, AuditService audit, Clock clock,
                              PlatformTransactionManager txManager,
                              @Value("${aftercare.security.bootstrap-admin-sub:}") String bootstrapSub) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
        this.bootstrapSub = bootstrapSub == null ? "" : bootstrapSub.trim();
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<StaffPrincipal> resolve(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        Optional<StaffPrincipal> found = lookup(sub);
        if (found.isPresent()) {
            return found;
        }
        if (bootstrapSub.isEmpty() || !bootstrapSub.equals(sub)) {
            return Optional.empty();
        }
        try {
            return writeTx.execute(status -> bootstrap(jwt, sub));
        } catch (DataIntegrityViolationException raced) {
            // Another request bootstrapped the same login first.
            return lookup(sub);
        }
    }

    private Optional<StaffPrincipal> lookup(String sub) {
        return readTx.execute(status -> repository.findFirstByAuth0SubjectAndActiveTrue(sub).map(StaffAccessService::principal));
    }

    private Optional<StaffPrincipal> bootstrap(Jwt jwt, String sub) {
        if (repository.findByAuth0Subject(sub).isPresent()
                || repository.countByAccessRoleAndActiveTrueAndAuth0SubjectIsNotNull(AccessRole.ADMIN) != 0) {
            return Optional.empty();
        }
        String name = jwt.getClaimAsString("name");
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            name = "Administrator";
        } else if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        String email = jwt.getClaimAsString("email");
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (email != null && (!EMAIL.matcher(email).matches() || email.length() > 255
                || repository.findByEmailIgnoreCase(email).isPresent())) {
            email = null;
        }
        Instant now = Instant.now(clock);
        StaffMember s = new StaffMember();
        s.setDisplayName(name);
        s.setEmail(email);
        s.setRole(StaffRole.OTHER);
        s.setAccessRole(AccessRole.ADMIN);
        s.setAuth0Subject(sub);
        s.setActive(true);
        s.setCreatedAt(now);
        s.setCreatedBySub(sub);
        s.setUpdatedAt(now);
        s.setUpdatedBySub(sub);
        s = repository.saveAndFlush(s);
        audit.recordChange(new Actor(sub, name), AuditAction.STAFF_BOOTSTRAPPED, null, AuditResource.STAFF, s.getId(), null);
        log.info("Bootstrapped administrator staff member {}", s.getId());
        return Optional.of(principal(s));
    }

    private static StaffPrincipal principal(StaffMember s) {
        return new StaffPrincipal(s.getId(), s.getAuth0Subject(), s.getDisplayName(), s.getEmail(), s.getRole(),
                s.getAccessRole());
    }
}
