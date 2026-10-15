package com.aftercare.aftercareapi.controller;

import com.aftercare.aftercareapi.Enums.*;
import com.aftercare.aftercareapi.Models.Dto.*;
import com.aftercare.aftercareapi.Services.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Patient documents. Content is always served as an attachment with locked-down headers. */
@RestController
public class DocumentController {

    private final DocumentService documents;
    private final ActorResolver actors;
    private final AuditService audit;

    public DocumentController(DocumentService documents, ActorResolver actors, AuditService audit) {
        this.documents = documents;
        this.actors = actors;
        this.audit = audit;
    }

    @GetMapping("/api/patients/{id:\\d+}/documents")
    public List<DocumentDTO> list(@PathVariable Long id) {
        return documents.list(id);
    }

    @PostMapping(value = "/api/patients/{id:\\d+}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDTO> upload(@PathVariable Long id,
                                              @RequestPart("file") MultipartFile file,
                                              @RequestParam("category") DocumentCategory category,
                                              @RequestParam(value = "title", required = false) String title,
                                              @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.upload(id, file, category, title, actors.from(jwt)));
    }

    @GetMapping("/api/documents/{docId:\\d+}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long docId, @AuthenticationPrincipal Jwt jwt) {
        DocumentService.Content c = documents.download(docId);
        audit.recordAccess(actors.from(jwt), AuditAction.DOCUMENT_DOWNLOADED, c.patientId(), AuditResource.DOCUMENT, c.id(), null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, c.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(c.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document-" + c.id() + "." + c.extension() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(c.bytes());
    }

    @PostMapping("/api/documents/{docId:\\d+}/delete")
    public ResponseEntity<Void> delete(@PathVariable Long docId, @Valid @RequestBody DocumentDeleteRequest body,
                                       @AuthenticationPrincipal Jwt jwt) {
        documents.delete(docId, body, actors.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
