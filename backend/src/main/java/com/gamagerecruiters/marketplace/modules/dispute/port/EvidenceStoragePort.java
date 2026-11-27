package com.gamagerecruiters.marketplace.modules.dispute.port;

import java.time.Instant;
import java.util.List;

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
     * An asset discovered during inventory reconciliation: its storage reference and when storage created it.
     * {@code createdAt} is null when the storage backend did not report a usable creation time; callers must then
     * treat the asset's age as unknown and skip it rather than risk deleting a still-in-flight upload (finding F6-C).
     */
    record StorageAsset(String publicId, Instant createdAt) {
    }

    /**
     * One bounded slice of an inventory traversal (finding F6-C): the assets gathered this call, plus the checkpoint
     * to resume from on the next call. {@code nextCursor} is null when the traversal has reached the end (the caller
     * should restart from the beginning next time); when non-null the caller must pass it back to continue where this
     * slice stopped — whether it stopped because the per-call page budget was reached or because a page request failed.
     */
    record StoragePage(List<StorageAsset> assets, String nextCursor) {
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

    /**
     * Lists evidence assets under {@code folderPrefix} for inventory reconciliation (finding F6-C), resuming from
     * {@code startCursor} (null to start at the beginning) and following at most {@code maxPages} pages so a single
     * call never makes unbounded API requests. The returned {@link StoragePage#nextCursor()} is the checkpoint to pass
     * back on the next call, or null once the whole collection has been traversed.
     */
    StoragePage listAssets(String folderPrefix, String startCursor, int maxPages);
}
