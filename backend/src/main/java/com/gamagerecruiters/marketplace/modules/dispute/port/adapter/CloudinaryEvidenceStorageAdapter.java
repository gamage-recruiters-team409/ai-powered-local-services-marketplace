package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import java.io.IOException;
import java.time.Instant;
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
    public List<StorageAsset> listAssets(String folderPrefix) {
        try {
            Map<?, ?> result = cloudinary.api().resources(ObjectUtils.asMap(
                    "type", DELIVERY_TYPE,
                    "prefix", folderPrefix,
                    "max_results", 500));
            Object resources = result.get("resources");
            if (resources instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(m -> (Map<?, ?>) m)
                        .filter(m -> m.get("public_id") != null)
                        .map(m -> new StorageAsset(m.get("public_id").toString(), parseCreatedAt(m.get("created_at"))))
                        .toList();
            }
            return List.of();
        } catch (Exception e) {
            log.warn("event=DISPUTE_EVIDENCE_LIST_FAILED prefix={} cause={}", folderPrefix, e.getClass().getSimpleName());
            return List.of();
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
