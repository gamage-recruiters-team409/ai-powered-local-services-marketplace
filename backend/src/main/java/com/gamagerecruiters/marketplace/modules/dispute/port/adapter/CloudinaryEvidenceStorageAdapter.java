package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;

/**
 * Evidence storage on Cloudinary. Reuses the application's existing
 * {@link Cloudinary} bean, which is configured only
 * from the environment variables CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY and
 * CLOUDINARY_API_SECRET (no new
 * dependency, no credentials in code). When any of them is missing the app
 * still starts and {@link #isAvailable()} is
 * false, so the API answers 503 EVIDENCE_STORAGE_UNAVAILABLE.
 *
 * <p>
 * Files are uploaded as {@code type=authenticated} (private) assets under
 * {@code disputes/<disputeId>/} with a
 * random public id; the original file name is never sent. Signed download URLs
 * expire. Only ids are logged.
 * </p>
 */
@Component
public class CloudinaryEvidenceStorageAdapter implements EvidenceStoragePort {

    static final String DELIVERY_TYPE = "authenticated";
    static final String RESOURCE_TYPE = "image";
    static final String FOLDER_PREFIX = "disputes/";

    /** Cloudinary Admin API page size for inventory listing (the API caps a single page at 500). */
    static final int PAGE_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(CloudinaryEvidenceStorageAdapter.class);

    private final Cloudinary cloudinary;

    public CloudinaryEvidenceStorageAdapter(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    @Override
    public boolean isAvailable() {
        return hasText(cloudinary.config.cloudName) && hasText(cloudinary.config.apiKey)
                && hasText(cloudinary.config.apiSecret);
    }

    @Override
    public StoredEvidence store(String disputeId, byte[] content, EvidenceType type) {
        Map<?, ?> options = ObjectUtils.asMap(
                "resource_type", RESOURCE_TYPE,
                "type", DELIVERY_TYPE,
                "folder", FOLDER_PREFIX + disputeId,
                "public_id", UUID.randomUUID().toString(),
                "format", type.format(),
                "overwrite", false,
                "use_filename", false,
                "unique_filename", false);
        try {
            Map<?, ?> result = cloudinary.uploader().upload(content, options);
            Object publicId = result.get("public_id");
            Object format = result.get("format");
            if (publicId == null) {
                throw new IllegalStateException("Evidence storage returned no reference");
            }
            return new StoredEvidence(publicId.toString(), format == null ? type.format() : format.toString());
        } catch (IOException e) {
            log.error("event=DISPUTE_EVIDENCE_STORE_FAILED disputeId={} cause={}", disputeId,
                    e.getClass().getSimpleName());
            throw new IllegalStateException("Evidence upload failed", e);
        }
    }

    @Override
    public String signedUrl(String publicId, String format, Instant expiresAt) {
        try {
            return cloudinary.privateDownload(publicId, format, ObjectUtils.asMap(
                    "resource_type", RESOURCE_TYPE,
                    "type", DELIVERY_TYPE,
                    "expires_at", expiresAt.getEpochSecond(),
                    "attachment", false));
        } catch (Exception e) {
            throw new IllegalStateException("Evidence link could not be created", e);
        }
    }

    @Override
    public void delete(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.asMap("resource_type", RESOURCE_TYPE,
                    "type", DELIVERY_TYPE, "invalidate", true));
        } catch (Exception e) {
            log.warn("event=DISPUTE_EVIDENCE_DELETE_FAILED publicId={} cause={}", publicId,
                    e.getClass().getSimpleName());
            throw new IllegalStateException("Evidence deletion failed for " + publicId, e);
        }
    }

    @Override
    public StoragePage listAssets(String folderPrefix, String startCursor, int maxPages) {
        int pageBudget = Math.max(1, maxPages);
        List<StorageAsset> assets = new ArrayList<>();
        String cursor = startCursor;
        String resumeCursor = startCursor;
        int pages = 0;
        boolean anyPageSucceeded = false;
        try {
            do {
                Map<String, Object> options = new HashMap<>();
                options.put("type", DELIVERY_TYPE);
                options.put("prefix", folderPrefix);
                options.put("max_results", PAGE_SIZE);
                if (cursor != null) {
                    options.put("next_cursor", cursor);
                }
                Map<?, ?> result = cloudinary.api().resources(options);
                collectAssets(result.get("resources"), assets);
                anyPageSucceeded = true;
                Object next = result.get("next_cursor");
                cursor = (next == null || next.toString().isBlank()) ? null : next.toString();
                resumeCursor = cursor;
                pages++;
            } while (cursor != null && pages < pageBudget);
            // cursor == null => whole collection traversed (restart next run); non-null => resume here next run.
            return new StoragePage(List.copyOf(assets), cursor);
        } catch (Exception e) {
            log.warn("event=DISPUTE_EVIDENCE_LIST_FAILED prefix={} examined={} cause={}",
                    folderPrefix, assets.size(), e.getClass().getSimpleName());
            if (!anyPageSucceeded && startCursor != null) {
                // The resume cursor itself failed (likely expired/invalid): restart from the beginning next run so the
                // scan can never get permanently stuck on a bad checkpoint.
                return new StoragePage(List.copyOf(assets), null);
            }
            // Resume from the last good checkpoint next run; deletion safety is still enforced downstream.
            return new StoragePage(List.copyOf(assets), resumeCursor);
        }
    }

    private static void collectAssets(Object resources, List<StorageAsset> out) {
        if (resources instanceof List<?> list) {
            list.stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<?, ?>) m)
                    .filter(m -> m.get("public_id") != null)
                    .map(m -> new StorageAsset(m.get("public_id").toString(), parseCreatedAt(m.get("created_at"))))
                    .forEach(out::add);
        }
    }

    /**
     * Cloudinary reports {@code created_at} as an ISO-8601 instant (e.g. {@code 2026-11-26T10:00:00Z}). A missing or
     * unparseable value yields null so the reconciler treats the asset's age as unknown and leaves it untouched.
     */
    private static Instant parseCreatedAt(Object createdAt) {
        if (createdAt == null) {
            return null;
        }
        try {
            return Instant.parse(createdAt.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
