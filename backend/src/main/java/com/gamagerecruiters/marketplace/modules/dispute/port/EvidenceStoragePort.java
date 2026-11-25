package com.gamagerecruiters.marketplace.modules.dispute.port;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType;

/**
 * Private storage for dispute evidence files. Files are stored as authenticated
 * (non-public) assets under
 * {@code disputes/<disputeId>/} with a random name, and are only ever opened
 * through a short-lived signed URL.
 * Implementations never log URLs, file names or contents.
 */
public interface EvidenceStoragePort {

    /**
     * A stored file: the storage reference to keep (internal, never sent to
     * clients).
     */
    record StoredEvidence(String publicId, String format) {
    }

    /**
     * False when storage is not configured (missing credentials): uploads and URLs
     * then answer 503.
     */
    boolean isAvailable();

    /**
     * Stores {@code content} (already validated) as a private asset of the dispute.
     *
     * @throws RuntimeException if the upload failed (nothing is recorded on the
     *                          dispute)
     */
    StoredEvidence store(String disputeId, byte[] content, EvidenceType type);

    /** A signed URL for the stored file that stops working at {@code expiresAt}. */
    String signedUrl(String publicId, String format, Instant expiresAt);

    /**
     * Best effort removal (used when the file could not be recorded on the
     * dispute).
     *
     * @throws RuntimeException if the deletion failed
     */
    void delete(String publicId);
}
