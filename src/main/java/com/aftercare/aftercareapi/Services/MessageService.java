package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Repositories.PatientAccessLinkRepository;
import com.aftercare.aftercareapi.Repositories.PatientMessageRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Staff side of secure messaging. One thread per patient; it is open while the patient has at least one usable access
 * link. Staff can always read, and send only while it is open. Bodies are plain text. Never logs message bodies.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    static final int MAX_BODY = 2000;

    /** Whether the thread is open and, when it is, the latest moment a usable link stays usable. */
    public record OpenState(boolean open, Instant until) {
        static final OpenState CLOSED = new OpenState(false, null);
    }

    /** A page of messages in ascending order. */
    public record Window(List<PatientMessage> items, boolean hasMore) {
    }

    private final PatientMessageRepository messages;
    private final PatientRepository patients;
    private final PatientAccessLinkRepository links;
    private final ActivityService activity;
    private final AuditService audit;
    private final Clock clock;

    public MessageService(PatientMessageRepository messages, PatientRepository patients, PatientAccessLinkRepository links,
                          ActivityService activity, AuditService audit, Clock clock) {
        this.messages = messages;
        this.patients = patients;
        this.links = links;
        this.activity = activity;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- staff operations

    @Transactional(readOnly = true)
    public ThreadDTO thread(Long patientId, Long before, int limit) {
        if (!patients.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        checkWindow(before, limit);
        Window w = window(patientId, before, limit);
        OpenState open = openState(links.findActiveForPatient(patientId));
        return new ThreadDTO(patientId, open.open(), open.until(),
                w.items().stream().map(m -> toDto(m, patientId)).toList(), w.hasMore());
    }

    @Transactional
    public MessageDTO send(Long patientId, MessageSendRequest req, Actor actor) {
        Patient patient = patients.findById(patientId).orElseThrow(PatientNotFoundException::new);
        Fields f = new Fields();
        String body = f.body("body", req.body(), 1, MAX_BODY);
        f.check();
        if (!openState(links.findActiveForPatient(patientId)).open()) {
            throw ApiException.conflict("MESSAGING_CLOSED", "Messaging is closed: the patient has no active link");
        }
        Instant now = Instant.now(clock);
        PatientMessage m = new PatientMessage();
        m.setPatient(patient);
        m.setSender(MessageSender.STAFF);
        m.setBody(body);
        m.setCreatedAt(now);
        m.setStaffSub(actor.sub());
        m.setStaffName(actor.name());
        m = messages.saveAndFlush(m);
        activity.record(patientId, ActivityKind.MESSAGE_TO_PATIENT, now, ActorType.STAFF, actor.sub(), actor.name(), m.getId(), null,
                Texts.preview(body));
        audit.recordChange(actor, AuditAction.MESSAGE_SENT, patientId, AuditResource.MESSAGE, m.getId(), "STAFF");
        log.info("Staff message id={} patient={}", m.getId(), patientId);
        return toDto(m, patientId);
    }

    @Transactional
    public void markRead(Long patientId, MarkReadRequest req, Actor actor) {
        if (!patients.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        long upTo = upToId(req);
        messages.markReadByStaff(patientId, upTo, Instant.now(clock), actor.sub());
    }

    @Transactional(readOnly = true)
    public List<ConversationDTO> inbox() {
        List<PatientMessage> latest = messages.findLatestPerPatient(PageRequest.of(0, 200));
        if (latest.isEmpty()) {
            return List.of();
        }
        List<Long> ids = latest.stream().map(m -> m.getPatient().getId()).toList();
        Map<Long, Long> unread = new HashMap<>();
        for (Object[] row : messages.unreadForStaffByPatient(ids)) {
            unread.put((Long) row[0], ((Number) row[1]).longValue());
        }
        Map<Long, List<PatientAccessLink>> byPatient = linksByPatient(links.findActiveForPatients(ids));
        List<ConversationDTO> out = new ArrayList<>();
        for (PatientMessage m : latest) {
            Patient p = m.getPatient();
            out.add(new ConversationDTO(p.getId(), p.getFirstName(), p.getLastName(), m.getCreatedAt(),
                    Texts.preview(m.getBody()), m.getSender(), unread.getOrDefault(p.getId(), 0L),
                    openState(byPatient.getOrDefault(p.getId(), List.of())).open()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public UnreadCountDTO unreadCount() {
        List<Object[]> rows = messages.unreadForStaffTotals();
        Object[] r = rows.get(0);
        return new UnreadCountDTO(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
    }

    // ---- shared with the patient side

    static void checkWindow(Long before, int limit) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (limit < 1 || limit > 100) {
            errors.put("limit", "must be between 1 and 100");
        }
        if (before != null && before < 1) {
            errors.put("before", "must be at least 1");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    static long upToId(MarkReadRequest req) {
        if (req == null || req.upToId() == null || req.upToId() < 1) {
            throw ApiException.validation("upToId", "must be at least 1");
        }
        return req.upToId();
    }

    /** The {@code limit} newest messages with id below {@code before}, returned in ascending order. */
    public Window window(Long patientId, Long before, int limit) {
        List<PatientMessage> found = messages.findByPatientIdAndIdLessThanOrderByIdDesc(patientId,
                before == null ? Long.MAX_VALUE : before, PageRequest.of(0, limit + 1));
        boolean more = found.size() > limit;
        List<PatientMessage> page = new ArrayList<>(more ? found.subList(0, limit) : found);
        Collections.reverse(page);
        return new Window(page, more);
    }

    /** Open when any non-revoked link has a usable target; the end is the latest moment such a link stays usable. */
    public OpenState openState(List<PatientAccessLink> active) {
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);
        Instant until = null;
        for (PatientAccessLink l : active) {
            Instant end = null;
            if (l.getFollowUp() != null && FollowUpService.linkUsable(l.getFollowUp(), today)) {
                end = l.getFollowUp().getEndDate().plusDays(2).atStartOfDay(clock.getZone()).toInstant();
            } else if (l.getAppointment() != null && AppointmentService.linkUsable(l.getAppointment(), now)) {
                end = l.getAppointment().getScheduledAt();
            }
            if (end != null && (until == null || end.isAfter(until))) {
                until = end;
            }
        }
        return until == null ? OpenState.CLOSED : new OpenState(true, until);
    }

    private static Map<Long, List<PatientAccessLink>> linksByPatient(List<PatientAccessLink> all) {
        Map<Long, List<PatientAccessLink>> out = new HashMap<>();
        for (PatientAccessLink l : all) {
            Long pid = l.getFollowUp() != null ? l.getFollowUp().getPatient().getId() : l.getAppointment().getPatient().getId();
            out.computeIfAbsent(pid, k -> new ArrayList<>()).add(l);
        }
        return out;
    }

    private MessageDTO toDto(PatientMessage m, Long patientId) {
        return new MessageDTO(m.getId(), patientId, m.getSender(), m.getSender() == MessageSender.STAFF ? m.getStaffName() : null,
                m.getBody(), m.getCreatedAt(), m.getReadByStaffAt(), m.getReadByPatientAt());
    }
}
