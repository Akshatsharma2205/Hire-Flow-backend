package com.kartgen.hireflow.document;

import java.util.UUID;

/**
 * Port for resume file storage: local disk in development, S3 in production.
 *
 * <p>Storage keys are opaque to callers: the adapter owns key generation and
 * guarantees that every successful {@link #store} call creates a brand-new,
 * unique object key that never overwrites an existing object. Callers use the
 * returned key for {@link #retrieve}, {@link #generateViewUrl} and
 * {@link #delete}.</p>
 */
public interface StoragePort {

    /**
     * Store file bytes and return the provider-relative object key.
     * Each call writes to a fresh unique key; existing objects are never overwritten.
     */
    String store(UUID orgId, String sha256, String mimeType, byte[] data);

    /** Retrieve file bytes by object key. */
    byte[] retrieve(String objectKey);

    /** Generate a short-lived, read-only view URL for the file. */
    String generateViewUrl(String objectKey, String fileName);

    /**
     * Delete the object identified by the key. Idempotent: deleting a missing
     * object is a no-op, so repeated compensation attempts are safe. A genuine
     * provider failure throws so the caller can log it for reconciliation.
     */
    void delete(String objectKey);

    /** Bucket name recorded on resume_files rows. */
    String bucketName();
}
