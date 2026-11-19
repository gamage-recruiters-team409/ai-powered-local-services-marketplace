
package com.gamagerecruiters.marketplace.modules.provider.filestorage;

import org.springframework.web.multipart.MultipartFile;

public interface ProfileImageStorageService {

    StoredProfileImage upload(
            String providerId,
            MultipartFile file
    );

    void delete(String publicId);
}
