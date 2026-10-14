package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.AccessLinkKind;
import com.aftercare.aftercareapi.Models.Appointment;
import com.aftercare.aftercareapi.Models.FollowUp;
import com.aftercare.aftercareapi.Models.PatientAccessLink;
import com.aftercare.aftercareapi.Repositories.PatientAccessLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issues and resolves patient access codes. The plaintext code is returned once and never stored or logged;
 * only its SHA-256 hex is persisted. Codes, hashes and public request paths must never be logged.
 */
@Service
public class AccessLinkService {

    public static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PatientAccessLinkRepository repository;
    private final Clock clock;

    public AccessLinkService(PatientAccessLinkRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 32 random bytes, base64url without padding (43 characters). */
    public static String generateCode() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public String issueForFollowUp(FollowUp f, String actor) {
        revoke(repository.findByFollowUpIdAndRevokedAtIsNull(f.getId()));
        String code = generateCode();
        save(AccessLinkKind.FOLLOW_UP, f, null, code, actor);
        return code;
    }

    @Transactional
    public String issueForAppointment(Appointment a, String actor) {
        revoke(repository.findByAppointmentIdAndRevokedAtIsNull(a.getId()));
        String code = generateCode();
        save(AccessLinkKind.APPOINTMENT, null, a, code, actor);
        return code;
    }

    /** Seeder only: issues a link with a caller-chosen code. Public because the seeder lives in the config package. */
    @Transactional
    public String issueWithCode(Object target, String code, String actor) {
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Access code must be 43 base64url characters");
        }
        if (target instanceof FollowUp f) {
            revoke(repository.findByFollowUpIdAndRevokedAtIsNull(f.getId()));
            save(AccessLinkKind.FOLLOW_UP, f, null, code, actor);
        } else if (target instanceof Appointment a) {
            revoke(repository.findByAppointmentIdAndRevokedAtIsNull(a.getId()));
            save(AccessLinkKind.APPOINTMENT, null, a, code, actor);
        } else {
            throw new IllegalArgumentException("Unsupported target");
        }
        return code;
    }

    /** Empty for malformed, unknown or revoked codes. The caller then checks the target's validity. */
    @Transactional(readOnly = true)
    public Optional<PatientAccessLink> resolve(String code) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            return Optional.empty();
        }
        String hash = hash(code);
        Optional<PatientAccessLink> link = repository.findByCodeHash(hash);
        if (link.isEmpty()) {
            return Optional.empty();
        }
        PatientAccessLink l = link.get();
        boolean same = MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                l.getCodeHash().getBytes(StandardCharsets.US_ASCII));
        if (!same || l.getRevokedAt() != null) {
            return Optional.empty();
        }
        return link;
    }

    @Transactional(readOnly = true)
    public Instant issuedAtForFollowUp(Long followUpId) {
        return repository.findByFollowUpIdAndRevokedAtIsNull(followUpId).stream()
                .map(PatientAccessLink::getCreatedAt).max(Instant::compareTo).orElse(null);
    }

    @Transactional(readOnly = true)
    public Instant issuedAtForAppointment(Long appointmentId) {
        return repository.findByAppointmentIdAndRevokedAtIsNull(appointmentId).stream()
                .map(PatientAccessLink::getCreatedAt).max(Instant::compareTo).orElse(null);
    }

    @Transactional(readOnly = true)
    public Map<Long, Instant> issuedAtForFollowUps(Collection<Long> ids) {
        Map<Long, Instant> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (PatientAccessLink l : repository.findByFollowUpIdInAndRevokedAtIsNull(ids)) {
            out.merge(l.getFollowUp().getId(), l.getCreatedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<Long, Instant> issuedAtForAppointments(Collection<Long> ids) {
        Map<Long, Instant> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (PatientAccessLink l : repository.findByAppointmentIdInAndRevokedAtIsNull(ids)) {
            out.merge(l.getAppointment().getId(), l.getCreatedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    @Transactional
    public void deleteForAppointment(Long appointmentId) {
        repository.deleteAll(repository.findByAppointmentId(appointmentId));
        repository.flush();
    }

    private void revoke(List<PatientAccessLink> links) {
        Instant now = Instant.now(clock);
        links.forEach(l -> l.setRevokedAt(now));
        repository.saveAll(links);
        repository.flush();
    }

    private void save(AccessLinkKind kind, FollowUp f, Appointment a, String code, String actor) {
        repository.save(PatientAccessLink.builder()
                .kind(kind).followUp(f).appointment(a)
                .codeHash(hash(code))
                .createdAt(Instant.now(clock))
                .createdBy(actor)
                .build());
    }
}
