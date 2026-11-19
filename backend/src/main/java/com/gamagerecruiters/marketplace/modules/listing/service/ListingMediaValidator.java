package com.gamagerecruiters.marketplace.modules.listing.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ApiException;

/**
 * Validates and sanitizes service listing media URLs (FR-LIST-004):
 * 1 to 8 valid HTTP(S) image URLs ending in jpg/jpeg/png/webp on publish,
 * and ensures {@code coverImageUrl} belongs to the listing's images.
 */
@Component
public class ListingMediaValidator {

    public static final int MAX_IMAGES = 8;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");

    public ValidatedMedia validate(List<String> rawImages, String rawCoverImageUrl, boolean publishing) {
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        if (rawImages != null) {
            for (String rawUrl : rawImages) {
                if (rawUrl == null || rawUrl.isBlank()) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_MEDIA_URL",
                            "Image URLs must not be blank"
                    );
                }
                String trimmed = rawUrl.trim();
                validateImageUrl(trimmed);
                sanitized.add(trimmed);
            }
        }

        if (sanitized.size() > MAX_IMAGES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "TOO_MANY_IMAGES",
                    "A listing can contain at most " + MAX_IMAGES + " images"
            );
        }

        if (publishing && sanitized.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "LISTING_IMAGES_REQUIRED",
                    "At least 1 valid image URL is required before publishing a listing"
            );
        }

        List<String> imageList = new ArrayList<>(sanitized);
        String coverImage = resolveCoverImageUrl(rawCoverImageUrl, imageList);
        return new ValidatedMedia(imageList, coverImage);
    }

    private void validateImageUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();

            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || host == null
                    || host.isBlank()
                    || path == null
                    || path.isBlank()) {
                throw invalidUrlException(url);
            }

            String lowerPath = path.toLowerCase(Locale.ROOT);
            boolean supportedExtension = ALLOWED_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
            if (!supportedExtension) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "UNSUPPORTED_IMAGE_FORMAT",
                        "Image URL must point to a JPG, JPEG, PNG, or WEBP file"
                );
            }
        } catch (URISyntaxException e) {
            throw invalidUrlException(url);
        }
    }

    private String resolveCoverImageUrl(String rawCoverImageUrl, List<String> images) {
        if (rawCoverImageUrl == null || rawCoverImageUrl.isBlank()) {
            return images.isEmpty() ? null : images.get(0);
        }

        String trimmedCover = rawCoverImageUrl.trim();
        validateImageUrl(trimmedCover);
        if (!images.contains(trimmedCover)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_COVER_IMAGE",
                    "coverImageUrl must match one of the listing images"
            );
        }
        return trimmedCover;
    }

    private ApiException invalidUrlException(String url) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_MEDIA_URL",
                "Image URL must be a valid http(s) URL: " + url
        );
    }

    public record ValidatedMedia(List<String> images, String coverImageUrl) {
    }
}
