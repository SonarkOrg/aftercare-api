package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Exceptions.ApiException;
import com.aftercare.aftercareapi.Exceptions.Patients.PatientNotFoundException;
import com.aftercare.aftercareapi.Models.Dto.DocumentDTO;
import com.aftercare.aftercareapi.Models.Dto.DocumentDeleteRequest;
import com.aftercare.aftercareapi.Models.Patient;
import com.aftercare.aftercareapi.Models.PatientDocument;
import com.aftercare.aftercareapi.Repositories.PatientDocumentRepository;
import com.aftercare.aftercareapi.Repositories.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Patient documents: PDF, JPEG and PNG up to 10 MB, typed by magic bytes. Soft delete keeps the row and the blob.
 * Never logs titles, filenames or bytes.
 */
@Service
public class DocumentService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** What the controller needs to stream a download. */
    public record Content(Long id, Long patientId, String contentType, long sizeBytes, byte[] bytes) {
        public String extension() {
            return switch (contentType) {
                case "application/pdf" -> "pdf";
                case "image/jpeg" -> "jpg";
                default -> "png";
            };
        }
    }

    private final PatientDocumentRepository documents;
    private final PatientRepository patients;
    private final DocumentStorage storage;
    private final ActivityService activity;
    private final AuditService audit;
    private final Clock clock;

    public DocumentService(PatientDocumentRepository documents, PatientRepository patients, DocumentStorage storage,
                           ActivityService activity, AuditService audit, Clock clock) {
        this.documents = documents;
        this.patients = patients;
        this.storage = storage;
        this.activity = activity;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DocumentDTO> list(Long patientId) {
        if (!patients.existsById(patientId)) {
            throw new PatientNotFoundException();
        }
        return documents.findByPatientIdAndDeletedAtIsNullOrderByUploadedAtDescIdDesc(patientId).stream()
                .map(d -> toDto(d, patientId)).toList();
    }

    @Transactional
    public DocumentDTO upload(Long patientId, MultipartFile file, DocumentCategory category, String titleParam, Actor actor) {
        Patient patient = patients.findById(patientId).orElseThrow(PatientNotFoundException::new);
        if (file == null) {
            throw ApiException.validation("file", "required");
        }
        if (file.isEmpty()) {
            throw ApiException.validation("file", "must not be empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.tooLarge();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read upload", e);
        }
        if (bytes.length > MAX_BYTES) {
            throw ApiException.tooLarge();
        }
        String type = FileTypeSniffer.sniff(Arrays.copyOf(bytes, Math.min(bytes.length, 16)))
                .orElseThrow(ApiException::unsupportedMedia);
        String filename = sanitizeFilename(file.getOriginalFilename());
        Fields f = new Fields();
        String title = f.optional("title", titleParam, 120);
        f.check();
        if (title == null) {
            title = defaultTitle(filename);
        }
        Instant now = Instant.now(clock);
        PatientDocument d = new PatientDocument();
        d.setPatient(patient);
        d.setTitle(title);
        d.setCategory(category);
        d.setContentType(type);
        d.setSizeBytes(bytes.length);
        d.setSha256(sha256(bytes));
        d.setOriginalFilename(filename);
        d.setStorageKey(storage.store(bytes));
        d.setUploadedAt(now);
        d.setUploadedBySub(actor.sub());
        d.setUploadedByName(actor.name());
        d = documents.saveAndFlush(d);
        activity.record(patientId, ActivityKind.DOCUMENT_UPLOADED, now, ActorType.STAFF, actor.sub(), actor.name(), d.getId(), null,
                "Document uploaded: " + title + " (" + Texts.humanize(category) + ")");
        audit.recordChange(actor, AuditAction.DOCUMENT_UPLOADED, patientId, AuditResource.DOCUMENT, d.getId(),
                category.name() + ", " + bytes.length + " bytes");
        log.info("Uploaded document id={} patient={} bytes={}", d.getId(), patientId, bytes.length);
        return toDto(d, patientId);
    }

    @Transactional(readOnly = true)
    public Content download(Long docId) {
        PatientDocument d = documents.findByIdAndDeletedAtIsNull(docId).orElseThrow(() -> ApiException.notFound("Not found"));
        byte[] bytes = storage.load(d.getStorageKey());
        return new Content(d.getId(), d.getPatient().getId(), d.getContentType(), d.getSizeBytes(), bytes);
    }

    @Transactional
    public void delete(Long docId, DocumentDeleteRequest req, Actor actor) {
        PatientDocument d = documents.findByIdAndDeletedAtIsNull(docId).orElseThrow(() -> ApiException.notFound("Not found"));
        Fields f = new Fields();
        String reason = f.body("reason", req.reason(), 3, 500);
        f.check();
        Instant now = Instant.now(clock);
        d.setDeletedAt(now);
        d.setDeletedBySub(actor.sub());
        d.setDeletedByName(actor.name());
        d.setDeleteReason(reason);
        documents.saveAndFlush(d);
        Long patientId = d.getPatient().getId();
        activity.record(patientId, ActivityKind.DOCUMENT_DELETED, now, ActorType.STAFF, actor.sub(), actor.name(), d.getId(), null,
                "Document deleted: " + d.getTitle());
        audit.recordChange(actor, AuditAction.DOCUMENT_DELETED, patientId, AuditResource.DOCUMENT, d.getId(), "reason: " + reason);
        log.info("Deleted document id={} patient={}", d.getId(), patientId);
    }

    /** Last path segment, control characters removed, at most 255 characters; "document" when nothing is left. */
    static String sanitizeFilename(String original) {
        String name = original == null ? "" : original;
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(cut + 1).replaceAll("[\\u0000-\\u001F\\u007F-\\u009F\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
        if (name.length() > 255) {
            name = name.substring(0, 255);
        }
        return name.isEmpty() ? "document" : name;
    }

    /** The filename without its extension, at most 120 characters; "Document" when empty. */
    static String defaultTitle(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = (dot > 0 ? filename.substring(0, dot) : filename).trim();
        if (base.length() > 120) {
            base = base.substring(0, 120).trim();
        }
        return base.isEmpty() ? "Document" : base;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private DocumentDTO toDto(PatientDocument d, Long patientId) {
        return new DocumentDTO(d.getId(), patientId, d.getTitle(), d.getCategory(), d.getContentType(), d.getSizeBytes(),
                d.getOriginalFilename(), d.getSha256(), d.getUploadedAt(), d.getUploadedByName());
    }
}
