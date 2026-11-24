package com.gamagerecruiters.marketplace.modules.customer.service;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.gamagerecruiters.marketplace.common.exception.ApiException;

@Service
public class CloudinaryCustomerProfileImageStorage implements CustomerProfileImageStorage {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryCustomerProfileImageStorage.class);

    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png"
    );

    private static final String BASE_FOLDER = "localserve/customer-profile";
    private static final String RESOURCE_TYPE = "image";
    private static final String DELIVERY_TYPE = "upload";

    private final Cloudinary cloudinary;

    public CloudinaryCustomerProfileImageStorage(
            @Qualifier("verificationCloudinary") Cloudinary cloudinary
    ) {
        this.cloudinary = cloudinary;
    }

    @Override
    public StoredProfileImage upload(String customerId, MultipartFile file) {
        if (customerId == null || customerId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CUSTOMER_ID", "Customer ID is required");
        }

        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Profile image file is required");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "Profile image must not exceed 5 MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT).trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE", "Only JPEG and PNG images are supported");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException ex) {
            log.warn("Failed to read customer profile photo bytes: userId={}, error={}",
                    customerId, ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNREADABLE_FILE", "Unable to read profile image");
        }

        if (fileBytes == null || fileBytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Profile image file is empty");
        }

        // Verify real file signature (magic bytes)
        String normalizedType = contentType.toLowerCase(Locale.ROOT).trim();
        if (normalizedType.equals("image/jpeg") || normalizedType.equals("image/jpg")) {
            if (!isJpegSignature(fileBytes)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_SIGNATURE", "File content does not match JPEG format");
            }
        } else if (normalizedType.equals("image/png")) {
            if (!isPngSignature(fileBytes)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_SIGNATURE", "File content does not match PNG format");
            }
        }

        String safeCustomerId = sanitizePathSegment(customerId);
        String publicId = UUID.randomUUID().toString();

        Map<String, Object> options = ObjectUtils.asMap(
                "resource_type", RESOURCE_TYPE,
                "type", DELIVERY_TYPE,
                "folder", BASE_FOLDER + "/" + safeCustomerId,
                "public_id", publicId,
                "overwrite", false
        );

        try {
            Map<?, ?> result = cloudinary.uploader().upload(fileBytes, options);

            String uploadedPublicId = stringValue(result.get("public_id"));
            String secureUrl = stringValue(result.get("secure_url"));

            if (uploadedPublicId == null || secureUrl == null) {
                log.warn("Cloudinary returned incomplete response: userId={}", customerId);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "Image storage returned an incomplete response");
            }

            return new StoredProfileImage(uploadedPublicId, secureUrl);
        } catch (ApiException apiEx) {
            throw apiEx;
        } catch (Exception ex) {
            log.warn("Customer profile photo upload failed: userId={}, error={}",
                    customerId, ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "Profile image upload failed");
        }
    }

    @Override
    public void delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
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
        } catch (Exception ex) {
            log.warn("Failed to delete profile image from storage: error={}", ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "Failed to delete profile image");
        }
    }

    private boolean isJpegSignature(byte[] bytes) {
        if (bytes == null || bytes.length < 3) {
            return false;
        }
        return (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isPngSignature(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return false;
        }
        return (bytes[0] & 0xFF) == 0x89
                && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E
                && (bytes[3] & 0xFF) == 0x47
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A;
    }

    private String sanitizePathSegment(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
