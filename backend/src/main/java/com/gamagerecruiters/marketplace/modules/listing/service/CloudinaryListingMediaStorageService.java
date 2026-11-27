package com.gamagerecruiters.marketplace.modules.listing.service;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingMediaUploadResponse;

/**
 * Uploads validated service listing portfolio images (FR-LIST-004: JPG, PNG, WEBP up to 5 MB)
 * to Cloudinary using the shared {@code verificationCloudinary} client without modifying external modules.
 */
@Service
public class CloudinaryListingMediaStorageService implements ListingMediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryListingMediaStorageService.class);

    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private static final String BASE_FOLDER = "localserve/service-listings";
    private static final String RESOURCE_TYPE = "image";
    private static final String DELIVERY_TYPE = "upload";
    private static final Pattern SAFE_ASSET_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern VERSION_PREFIX_PATTERN = Pattern.compile("^v\\d+/");
    private static final Pattern SUPPORTED_EXT_PATTERN = Pattern.compile("\\.(jpg|jpeg|png|webp)$", Pattern.CASE_INSENSITIVE);

    private final Cloudinary cloudinary;

    public CloudinaryListingMediaStorageService(
            @Qualifier("verificationCloudinary") Cloudinary cloudinary
    ) {
        this.cloudinary = cloudinary;
    }

    @Override
    public ListingMediaUploadResponse uploadPortfolioImage(String providerUserId, MultipartFile file) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_ID",
                    "Authenticated provider ID is required"
            );
        }

        if (file == null || file.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MEDIA_FILE",
                    "Portfolio image file is required"
            );
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "FILE_TOO_LARGE",
                    "Each portfolio image must not exceed 5 MB"
            );
        }

        String rawContentType = file.getContentType();
        String normalizedContentType = rawContentType == null
                ? ""
                : rawContentType.trim().toLowerCase(Locale.ROOT);

        if (!ALLOWED_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_IMAGE_FORMAT",
                    "Only JPG, PNG, and WEBP portfolio images are supported"
            );
        }

        if (!isCloudinaryConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MEDIA_STORAGE_NOT_CONFIGURED",
                    "Direct cloud image upload is not configured on this server. Paste an HTTPS image URL instead."
            );
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MEDIA_FILE",
                    "Unable to read uploaded portfolio image"
            );
        }

        String safeProviderId = providerUserId.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        String publicId = UUID.randomUUID().toString();
        String targetFormat = resolveFormatExtension(normalizedContentType);
        String folderPath = BASE_FOLDER + "/" + safeProviderId;

        Map<String, Object> options = ObjectUtils.asMap(
                "resource_type", RESOURCE_TYPE,
                "type", DELIVERY_TYPE,
                "folder", folderPath,
                "asset_folder", folderPath,
                "public_id", publicId,
                "format", targetFormat,
                "overwrite", false
        );

        try {
            Map<?, ?> result = cloudinary.uploader().upload(fileBytes, options);
            String uploadedPublicId = stringValue(result.get("public_id"));
            String secureUrl = stringValue(result.get("secure_url"));

            if (uploadedPublicId == null || uploadedPublicId.isBlank()
                    || secureUrl == null || secureUrl.isBlank()) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "MEDIA_UPLOAD_FAILED",
                        "Cloud storage returned an incomplete image response"
                );
            }

            String safeFileName = resolveSafeFileName(file.getOriginalFilename(), targetFormat);
            return new ListingMediaUploadResponse(
                    uploadedPublicId,
                    ensureSupportedUrlExtension(secureUrl, targetFormat),
                    safeFileName,
                    normalizedContentType,
                    file.getSize()
            );
        } catch (IOException ex) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "MEDIA_UPLOAD_FAILED",
                    "Portfolio image upload to cloud storage failed"
            );
        }
    }

    @Override
    public void deletePortfolioImageByPublicId(String providerUserId, String publicId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_ID",
                    "Authenticated provider ID is required"
            );
        }
        String normalizedPublicId = validateOwnedPublicId(providerUserId, publicId);

        if (!isCloudinaryConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MEDIA_STORAGE_NOT_CONFIGURED",
                    "Direct cloud image storage is not configured on this server"
            );
        }

        try {
            cloudinary.uploader().destroy(
                    normalizedPublicId,
                    ObjectUtils.asMap(
                            "resource_type", RESOURCE_TYPE,
                            "type", DELIVERY_TYPE,
                            "invalidate", true
                    )
            );
        } catch (IOException | RuntimeException ex) {
            log.warn("event=LISTING_MEDIA_DELETE_FAILED providerId={} publicId={} cause={}",
                    providerUserId, normalizedPublicId, ex.getClass().getSimpleName());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "MEDIA_DELETE_FAILED",
                    "Portfolio image deletion from cloud storage failed"
            );
        }
    }

    @Override
    public boolean deletePortfolioImageByUrlBestEffort(String providerUserId, String imageUrl) {
        if (!hasText(providerUserId) || !hasText(imageUrl) || !isCloudinaryConfigured()) {
            return false;
        }
        Optional<String> ownedPublicId = extractOwnedPublicIdFromUrl(providerUserId, imageUrl);
        if (ownedPublicId.isEmpty()) {
            return false;
        }
        String publicId = ownedPublicId.get();
        try {
            cloudinary.uploader().destroy(
                    publicId,
                    ObjectUtils.asMap(
                            "resource_type", RESOURCE_TYPE,
                            "type", DELIVERY_TYPE,
                            "invalidate", true
                    )
            );
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("event=LISTING_MEDIA_CLEANUP_FAILED providerId={} publicId={} cause={}",
                    providerUserId, publicId, ex.getClass().getSimpleName());
            return false;
        }
    }

    Optional<String> extractOwnedPublicIdFromUrl(String providerUserId, String imageUrl) {
        if (!hasText(providerUserId) || !hasText(imageUrl)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(imageUrl.trim());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (uri.getHost() == null || !"res.cloudinary.com".equalsIgnoreCase(uri.getHost())) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String configuredCloud = cloudinary != null && cloudinary.config != null
                ? cloudinary.config.cloudName
                : null;
        if (!hasText(configuredCloud)) {
            return Optional.empty();
        }
        String expectedUploadPrefix = "/" + configuredCloud.trim() + "/" + RESOURCE_TYPE + "/" + DELIVERY_TYPE + "/";
        if (!path.startsWith(expectedUploadPrefix)) {
            return Optional.empty();
        }
        String remainder = path.substring(expectedUploadPrefix.length());
        remainder = VERSION_PREFIX_PATTERN.matcher(remainder).replaceFirst("");
        String withoutExtension = SUPPORTED_EXT_PATTERN.matcher(remainder).replaceFirst("");
        if (!isStrictlyOwnedPublicId(providerUserId, withoutExtension)) {
            return Optional.empty();
        }
        return Optional.of(withoutExtension);
    }

    private String validateOwnedPublicId(String providerUserId, String rawPublicId) {
        if (rawPublicId == null || rawPublicId.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MEDIA_PUBLIC_ID",
                    "Media publicId is required"
            );
        }
        String trimmed = rawPublicId.trim();
        if (trimmed.contains("..") || trimmed.contains("\\") || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MEDIA_PUBLIC_ID",
                    "Media publicId format is invalid"
            );
        }
        String expectedPrefix = expectedProviderPrefix(providerUserId);
        if (!trimmed.startsWith(expectedPrefix)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "MEDIA_OWNERSHIP_DENIED",
                    "You can only delete portfolio images uploaded to your own listing folder"
            );
        }
        String assetId = trimmed.substring(expectedPrefix.length());
        if (!SAFE_ASSET_ID_PATTERN.matcher(assetId).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_MEDIA_PUBLIC_ID",
                    "Media publicId asset identifier is invalid"
            );
        }
        return trimmed;
    }

    private boolean isStrictlyOwnedPublicId(String providerUserId, String candidate) {
        if (!hasText(candidate) || candidate.contains("..") || candidate.contains("\\")) {
            return false;
        }
        String expectedPrefix = expectedProviderPrefix(providerUserId);
        if (!candidate.startsWith(expectedPrefix)) {
            return false;
        }
        String assetId = candidate.substring(expectedPrefix.length());
        return SAFE_ASSET_ID_PATTERN.matcher(assetId).matches();
    }

    private String expectedProviderPrefix(String providerUserId) {
        String safeProviderId = providerUserId.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return BASE_FOLDER + "/" + safeProviderId + "/";
    }

    private boolean isCloudinaryConfigured() {
        if (cloudinary == null || cloudinary.config == null) {
            return false;
        }
        return hasText(cloudinary.config.cloudName)
                && hasText(cloudinary.config.apiKey)
                && hasText(cloudinary.config.apiSecret);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String resolveFormatExtension(String contentType) {
        if ("image/png".equals(contentType)) {
            return "png";
        }
        if ("image/webp".equals(contentType)) {
            return "webp";
        }
        return "jpg";
    }

    private String ensureSupportedUrlExtension(String url, String fallbackExt) {
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")) {
            return trimmed;
        }
        return trimmed + "." + fallbackExt;
    }

    private String resolveSafeFileName(String originalName, String fallbackExt) {
        if (originalName == null || originalName.isBlank()) {
            return "portfolio-image." + fallbackExt;
        }
        String base = originalName.trim().replaceAll("[\\\\/]+", "_");
        return base.isEmpty() ? "portfolio-image." + fallbackExt : base;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
