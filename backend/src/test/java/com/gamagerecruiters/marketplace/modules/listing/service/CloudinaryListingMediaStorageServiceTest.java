package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingMediaUploadResponse;

class CloudinaryListingMediaStorageServiceTest {

    private Cloudinary configuredCloudinary(Uploader uploader) {
        Cloudinary cloudinary = new Cloudinary(Map.of(
                "cloud_name", "demo-cloud",
                "api_key", "demo-key",
                "api_secret", "demo-secret",
                "secure", "true"
        ));
        Cloudinary spy = org.mockito.Mockito.spy(cloudinary);
        when(spy.uploader()).thenReturn(uploader);
        return spy;
    }

    @Test
    @DisplayName("Uploads valid JPG/PNG/WEBP image up to 5 MB and returns validator-compatible secureUrl")
    void uploadsValidImageSuccessfully() throws IOException {
        Uploader uploader = mock(Uploader.class);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
                "public_id", "localserve/service-listings/prov_1/img-123",
                "secure_url", "https://res.cloudinary.com/demo-cloud/image/upload/v1/localserve/service-listings/prov_1/img-123.webp"
        ));

        CloudinaryListingMediaStorageService service =
                new CloudinaryListingMediaStorageService(configuredCloudinary(uploader));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "ac-wash-photo.webp",
                "image/webp",
                new byte[]{1, 2, 3, 4}
        );

        ListingMediaUploadResponse response = service.uploadPortfolioImage("prov-1", file);
        assertThat(response.publicId()).isEqualTo("localserve/service-listings/prov_1/img-123");
        assertThat(response.secureUrl()).endsWith(".webp");
        assertThat(response.fileName()).isEqualTo("ac-wash-photo.webp");
        assertThat(response.contentType()).isEqualTo("image/webp");
        assertThat(response.sizeBytes()).isEqualTo(4L);

        ListingMediaValidator validator = new ListingMediaValidator();
        assertThat(validator.validate(java.util.List.of(response.secureUrl()), response.secureUrl(), true).images())
                .containsExactly(response.secureUrl());
    }

    @Test
    @DisplayName("Rejects empty file, unsupported MIME format, and files exceeding 5 MB with 400 ApiException")
    void rejectsInvalidFiles() {
        Uploader uploader = mock(Uploader.class);
        CloudinaryListingMediaStorageService service =
                new CloudinaryListingMediaStorageService(configuredCloudinary(uploader));

        MockMultipartFile empty = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);
        assertThatThrownBy(() -> service.uploadPortfolioImage("prov-1", empty))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getErrorCode()).isEqualTo("INVALID_MEDIA_FILE");
                });

        MockMultipartFile pdfFile = new MockMultipartFile("file", "quote.pdf", "application/pdf", new byte[]{1, 2});
        assertThatThrownBy(() -> service.uploadPortfolioImage("prov-1", pdfFile))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getErrorCode()).isEqualTo("UNSUPPORTED_IMAGE_FORMAT");
                });

        byte[] oversized = new byte[(int) CloudinaryListingMediaStorageService.MAX_FILE_SIZE_BYTES + 1];
        MockMultipartFile bigFile = new MockMultipartFile("file", "huge.png", "image/png", oversized);
        assertThatThrownBy(() -> service.uploadPortfolioImage("prov-1", bigFile))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getErrorCode()).isEqualTo("FILE_TOO_LARGE");
                });
    }

    @Test
    @DisplayName("Fails gracefully with 503 MEDIA_STORAGE_NOT_CONFIGURED when Cloudinary credentials are empty")
    void failsGracefullyWhenUnconfigured() {
        Cloudinary unconfigured = new Cloudinary(Map.of(
                "cloud_name", "",
                "api_key", "",
                "api_secret", ""
        ));
        CloudinaryListingMediaStorageService service = new CloudinaryListingMediaStorageService(unconfigured);
        MockMultipartFile file = new MockMultipartFile("file", "work.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.uploadPortfolioImage("prov-1", file))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getErrorCode()).isEqualTo("MEDIA_STORAGE_NOT_CONFIGURED");
                });
    }

    @Test
    @DisplayName("Destroys provider-owned Cloudinary publicId with invalidate=true and blocks cross-provider IDOR or traversal")
    void deletesOwnedPublicIdAndBlocksIdorOrTraversal() throws IOException {
        Uploader uploader = mock(Uploader.class);
        when(uploader.destroy(eq("localserve/service-listings/prov-1/img-123"), anyMap()))
                .thenReturn(Map.of("result", "ok"));

        CloudinaryListingMediaStorageService service =
                new CloudinaryListingMediaStorageService(configuredCloudinary(uploader));

        service.deletePortfolioImageByPublicId("prov-1", "localserve/service-listings/prov-1/img-123");
        verify(uploader).destroy(eq("localserve/service-listings/prov-1/img-123"), anyMap());

        assertThatThrownBy(() -> service.deletePortfolioImageByPublicId(
                "prov-1", "localserve/service-listings/prov-2/img-999"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getErrorCode()).isEqualTo("MEDIA_OWNERSHIP_DENIED");
                });

        assertThatThrownBy(() -> service.deletePortfolioImageByPublicId(
                "prov-1", "localserve/service-listings/prov-1/../prov-2/img-999"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getErrorCode()).isEqualTo("INVALID_MEDIA_PUBLIC_ID");
                });
    }

    @Test
    @DisplayName("Best-effort URL cleanup destroys matching owned Cloudinary asset and safely ignores external or foreign URLs")
    void bestEffortUrlCleanupRespectsCloudOwnershipAndNeverThrows() throws IOException {
        Uploader uploader = mock(Uploader.class);
        when(uploader.destroy(eq("localserve/service-listings/prov-1/abc-1"), anyMap()))
                .thenReturn(Map.of("result", "ok"));

        CloudinaryListingMediaStorageService service =
                new CloudinaryListingMediaStorageService(configuredCloudinary(uploader));

        boolean cleaned = service.deletePortfolioImageByUrlBestEffort(
                "prov-1",
                "https://res.cloudinary.com/demo-cloud/image/upload/v1795543170/localserve/service-listings/prov-1/abc-1.png"
        );
        assertThat(cleaned).isTrue();
        verify(uploader).destroy(eq("localserve/service-listings/prov-1/abc-1"), anyMap());

        assertThat(service.deletePortfolioImageByUrlBestEffort(
                "prov-1", "https://cdn.example.com/external-photo.jpg")).isFalse();
        assertThat(service.deletePortfolioImageByUrlBestEffort(
                "prov-1", "https://res.cloudinary.com/other-cloud/image/upload/v1/localserve/service-listings/prov-1/abc-2.jpg")).isFalse();
        assertThat(service.deletePortfolioImageByUrlBestEffort(
                "prov-1", "https://res.cloudinary.com/demo-cloud/image/upload/v1/localserve/service-listings/prov-2/abc-3.jpg")).isFalse();
        verify(uploader, never()).destroy(eq("localserve/service-listings/prov-2/abc-3"), anyMap());

        when(uploader.destroy(eq("localserve/service-listings/prov-1/err-1"), anyMap()))
                .thenThrow(new IOException("Transient network error"));
        assertThat(service.deletePortfolioImageByUrlBestEffort(
                "prov-1",
                "https://res.cloudinary.com/demo-cloud/image/upload/v1/localserve/service-listings/prov-1/err-1.webp"
        )).isFalse();
    }
}
