package com.gamagerecruiters.marketplace.modules.verification.filestorage;

public record StoredVerificationFile(
        String assetId,
        String publicId,
        String resourceType,
        String deliveryType,
        String format,
        Long bytes,
        String originalFileName
) {
}