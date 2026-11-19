package com.gamagerecruiters.marketplace.modules.listing.service;

import java.time.Instant;

/**
 * Abstraction for direct cloud media uploads (S3 / Cloudinary pre-signed URLs) for future attachment wiring.
 */
public interface ListingMediaStoragePort {

    PresignedUploadSlot createPresignedUploadUrl(
            String providerUserId,
            String fileName,
            String contentType,
            long contentLengthBytes
    );

    record PresignedUploadSlot(
            String uploadUrl,
            String publicUrl,
            Instant expiresAt
    ) {
    }
}
