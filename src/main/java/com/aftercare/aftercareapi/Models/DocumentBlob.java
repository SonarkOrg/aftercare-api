package com.aftercare.aftercareapi.Models;

import com.aftercare.aftercareapi.Enums.*;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The bytes of a document, in their own table so listing documents never loads them. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_blobs")
public class DocumentBlob implements Persistable<String> {

    @Id
    @Column(length = 36)
    private String id;
    // No @Lob (that would be an oid/blob). Hibernate would pick "blob" on H2 for a 10 MB length, which H2 in
    // PostgreSQL mode rejects; "bytea" is valid on Postgres and on H2 in PostgreSQL mode. The 10 MB cap is enforced by DocumentService.
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] bytes;
    @Column(nullable = false)
    private Instant createdAt;
    @Transient
    private boolean fresh = true;

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.fresh = false;
    }
}
