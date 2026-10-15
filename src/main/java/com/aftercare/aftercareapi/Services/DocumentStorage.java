package com.aftercare.aftercareapi.Services;

/** Where document bytes live. The only implementation keeps them in Postgres; object storage would be a drop-in. */
public interface DocumentStorage {

    /** Stores the bytes and returns an opaque key. */
    String store(byte[] content);

    byte[] load(String key);

    /** For a future purge job; unused today. */
    void delete(String key);
}
