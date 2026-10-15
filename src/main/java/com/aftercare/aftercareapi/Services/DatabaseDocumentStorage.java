package com.aftercare.aftercareapi.Services;

import com.aftercare.aftercareapi.Models.DocumentBlob;
import com.aftercare.aftercareapi.Repositories.DocumentBlobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Stores document bytes in the {@code document_blobs} table, in the caller's transaction. */
@Component
public class DatabaseDocumentStorage implements DocumentStorage {

    private final DocumentBlobRepository repository;
    private final Clock clock;

    public DatabaseDocumentStorage(DocumentBlobRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public String store(byte[] content) {
        DocumentBlob blob = new DocumentBlob();
        blob.setId(UUID.randomUUID().toString());
        blob.setBytes(content);
        blob.setCreatedAt(Instant.now(clock));
        repository.save(blob);
        return blob.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] load(String key) {
        return repository.findById(key).map(DocumentBlob::getBytes)
                .orElseThrow(() -> new IllegalStateException("Document content is missing"));
    }

    @Override
    @Transactional
    public void delete(String key) {
        repository.deleteById(key);
    }
}
