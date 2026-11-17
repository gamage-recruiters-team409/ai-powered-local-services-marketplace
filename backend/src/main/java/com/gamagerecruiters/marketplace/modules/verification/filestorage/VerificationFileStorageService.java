
package com.gamagerecruiters.marketplace.modules.verification.filestorage;

import java.time.Instant;

import org.springframework.web.multipart.MultipartFile;

public interface VerificationFileStorageService {

    StoredVerificationFile upload(
            String providerId,
            MultipartFile file
    );

    String generateTemporaryAccessUrl(
            String publicId,
            String format,
            String resourceType,
            String deliveryType,
            Instant expiresAt
    );

    void delete(String publicId);
}
