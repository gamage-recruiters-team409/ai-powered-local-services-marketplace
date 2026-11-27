package com.gamagerecruiters.marketplace.modules.listing.service;

import org.springframework.web.multipart.MultipartFile;

import com.gamagerecruiters.marketplace.modules.listing.dto.ListingMediaUploadResponse;

/**
 * Port for uploading and safely cleaning up validated portfolio images for WP03 service listings (FR-LIST-004).
 */
public interface ListingMediaUploadService {

    ListingMediaUploadResponse uploadPortfolioImage(String providerUserId, MultipartFile file);

    /**
     * Destroys a provider-owned portfolio image by Cloudinary {@code publicId} after verifying
     * it belongs strictly under {@code localserve/service-listings/{providerId}/}.
     */
    default void deletePortfolioImageByPublicId(String providerUserId, String publicId) {
        // Default no-op for lightweight test doubles
    }

    /**
     * Best-effort cleanup of a removed portfolio image URL after a listing update is persisted.
     * Ignores external (non-Cloudinary) URLs and logs cloud errors without failing the listing save.
     *
     * @return {@code true} if an owned Cloudinary asset was destroyed, {@code false} if skipped or failed
     */
    default boolean deletePortfolioImageByUrlBestEffort(String providerUserId, String imageUrl) {
        return false;
    }
}
