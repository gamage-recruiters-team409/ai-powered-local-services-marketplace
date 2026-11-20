
package com.gamagerecruiters.marketplace.modules.provider.filestorage;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

@Service
public class CloudinaryProfileImageStorageService
        implements ProfileImageStorageService {

    private static final long MAX_FILE_SIZE_BYTES =
            5L * 1024L * 1024L;

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "image/webp"
            );

    private static final String BASE_FOLDER =
            "localserve/provider-profile";

    private static final String RESOURCE_TYPE = "image";

    private static final String DELIVERY_TYPE = "upload";

    private final Cloudinary cloudinary;

    public CloudinaryProfileImageStorageService(
            @Qualifier("verificationCloudinary")
            Cloudinary cloudinary
    ) {
        this.cloudinary = cloudinary;
    }

    @Override
    public StoredProfileImage upload(
            String providerId,
            MultipartFile file
    ) {

        validateProviderId(providerId);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Profile image is required"
            );
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Profile image must not exceed 5 MB"
            );
        }

        String contentType = file.getContentType();

        if (contentType == null
                || !ALLOWED_CONTENT_TYPES.contains(
                        contentType.toLowerCase(Locale.ROOT)
                )) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only JPEG, PNG and WEBP images are supported"
            );
        }

        byte[] fileBytes;

        try {
            fileBytes = file.getBytes();

        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to read profile image",
                    exception
            );
        }

        String safeProviderId = sanitizePathSegment(providerId);

        String publicId = UUID.randomUUID().toString();

        Map<String, Object> options = ObjectUtils.asMap(
                "resource_type", RESOURCE_TYPE,
                "type", DELIVERY_TYPE,
                "folder", BASE_FOLDER + "/" + safeProviderId,
                "public_id", publicId,
                "overwrite", false
        );

        try {

            Map<?, ?> result = cloudinary.uploader().upload(
                    fileBytes,
                    options
            );

            String uploadedPublicId =
                    stringValue(result.get("public_id"));

            String secureUrl =
                    stringValue(result.get("secure_url"));

            if (uploadedPublicId == null || secureUrl == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Cloudinary returned an incomplete profile image response"
                );
            }

            return new StoredProfileImage(
                    uploadedPublicId,
                    secureUrl
            );

        } catch (IOException exception) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Profile image upload failed",
                    exception
            );
        }
    }

    @Override
    public void delete(String publicId) {

        if (publicId == null || publicId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Profile image storage reference is required"
            );
        }

        try {

            cloudinary.uploader().destroy(
                    publicId.trim(),
                    ObjectUtils.asMap(
                            "resource_type", RESOURCE_TYPE,
                            "type", DELIVERY_TYPE,
                            "invalidate", true
                    )
            );

        } catch (IOException exception) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Profile image deletion failed",
                    exception
            );
        }
    }

    private void validateProviderId(String providerId) {

        if (providerId == null || providerId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Provider ID is required"
            );
        }
    }

    private String sanitizePathSegment(String value) {

        return value.trim().replaceAll(
                "[^A-Za-z0-9_-]",
                "_"
        );
    }

    private String stringValue(Object value) {

        return value == null
                ? null
                : String.valueOf(value);
    }
}
