
package com.gamagerecruiters.marketplace.modules.verification.filestorage;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

@Service
public class CloudinaryVerificationFileStorageService
        implements VerificationFileStorageService {

    private static final long MAX_FILE_SIZE_BYTES =
            10L * 1024L * 1024L;

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of(
                    "image/jpeg",
                    "image/png",
                    "application/pdf"
            );

    private static final String BASE_FOLDER =
            "localserve/provider-verification";

    private static final String RESOURCE_TYPE = "image";

    private static final String DELIVERY_TYPE = "authenticated";

    private final Cloudinary cloudinary;

    public CloudinaryVerificationFileStorageService(
            Cloudinary cloudinary
    ) {
        this.cloudinary = cloudinary;
    }

    @Override
    public StoredVerificationFile upload(
            String providerId,
            MultipartFile file
    ) {
        validateProviderId(providerId);
        validateFile(file);

        String safeProviderId =
                sanitizePathSegment(providerId);

        String publicId = UUID.randomUUID().toString();

        String originalFileName =
                sanitizeOriginalFileName(
                        file.getOriginalFilename()
                );

        Map<String, Object> options =
                ObjectUtils.asMap(
                        "resource_type", RESOURCE_TYPE,
                        "type", DELIVERY_TYPE,
                        "folder",
                        BASE_FOLDER
                                + "/"
                                + safeProviderId,
                        "public_id", publicId,
                        "filename_override",
                        originalFileName,
                        "overwrite", false
                );

        try {
            Map<?, ?> result =
                    cloudinary.uploader().upload(
                            file.getBytes(),
                            options
                    );

            return new StoredVerificationFile(
                    stringValue(
                            result.get("asset_id")
                    ),
                    stringValue(
                            result.get("public_id")
                    ),
                    stringValue(
                            result.get("resource_type")
                    ),
                    stringValue(
                            result.get("type")
                    ),
                    stringValue(
                            result.get("format")
                    ),
                    longValue(
                            result.get("bytes")
                    ),
                    originalFileName
            );

        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Verification document upload failed",
                    exception
            );
        }
    }

    @Override
    public String generateTemporaryAccessUrl(
            String publicId,
            String format,
            String resourceType,
            String deliveryType,
            Instant expiresAt
    ) {

        if (publicId == null || publicId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Verification document storage reference is missing"
            );
        }

        if (format == null || format.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Verification document format is missing"
            );
        }

        if (!RESOURCE_TYPE.equals(resourceType)) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Verification document resource type is invalid"
            );
        }

        if (!DELIVERY_TYPE.equals(deliveryType)) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Verification document delivery configuration is invalid"
            );
        }

        if (expiresAt == null
                || !expiresAt.isAfter(Instant.now())) {

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Verification document access expiry is invalid"
            );
        }

        try {

            return cloudinary.privateDownload(
                    publicId.trim(),
                    format.trim().toLowerCase(Locale.ROOT),
                    ObjectUtils.asMap(
                            "resource_type", RESOURCE_TYPE,
                            "type", DELIVERY_TYPE,
                            "expires_at", expiresAt.getEpochSecond(),
                            "attachment", false
                    )
            );

        } catch (Exception exception) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to generate secure verification document access",
                    exception
            );
        }
    }

    @Override
    public void delete(String publicId) {

        if (publicId == null
                || publicId.trim().isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cloudinary public ID is required"
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
                    "Verification document deletion failed",
                    exception
            );
        }
    }

    private void validateProviderId(
            String providerId
    ) {

        if (providerId == null
                || providerId.trim().isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Provider ID is required"
            );
        }
    }

    private void validateFile(
            MultipartFile file
    ) {

        if (file == null || file.isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Verification document is required"
            );
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Verification document must not exceed 10 MB"
            );
        }

        String contentType = file.getContentType();

        if (contentType == null
                || !ALLOWED_CONTENT_TYPES.contains(
                        contentType.toLowerCase(Locale.ROOT)
                )) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only PDF, JPEG and PNG verification documents are supported"
            );
        }
    }

    private String sanitizePathSegment(
            String value
    ) {

        return value.trim()
                .replaceAll(
                        "[^A-Za-z0-9_-]",
                        "_"
                );
    }

    private String sanitizeOriginalFileName(
            String originalFileName
    ) {

        if (originalFileName == null
                || originalFileName.isBlank()) {

            return "verification-document";
        }

        String normalized =
                originalFileName
                        .replace("\\", "_")
                        .replace("/", "_")
                        .trim();

        return normalized.isEmpty()
                ? "verification-document"
                : normalized;
    }

    private String stringValue(Object value) {

        return value == null
                ? null
                : String.valueOf(value);
    }

    private Long longValue(Object value) {

        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value == null) {
            return null;
        }

        try {

            return Long.valueOf(
                    String.valueOf(value)
            );

        } catch (NumberFormatException exception) {

            return null;
        }
    }
}
