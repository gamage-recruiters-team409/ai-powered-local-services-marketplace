package com.gamagerecruiters.marketplace.modules.verification.filestorage;

import org.springframework.web.multipart.MultipartFile;

public interface VerificationFileStorageService {

    StoredVerificationFile upload(
            String providerId,
            MultipartFile file
    );

    void delete(String publicId);
}