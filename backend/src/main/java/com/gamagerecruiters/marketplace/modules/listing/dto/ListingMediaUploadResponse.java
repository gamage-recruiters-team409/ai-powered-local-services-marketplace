package com.gamagerecruiters.marketplace.modules.listing.dto;

public record ListingMediaUploadResponse(
        String publicId,
        String secureUrl,
        String fileName,
        String contentType,
        long sizeBytes
) {
}
