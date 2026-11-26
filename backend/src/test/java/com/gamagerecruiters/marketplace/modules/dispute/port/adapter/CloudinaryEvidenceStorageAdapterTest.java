package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;

/** No network: availability comes from the config, and a signed download URL is built locally. */
class CloudinaryEvidenceStorageAdapterTest {

    @Test
    @DisplayName("missing CLOUDINARY_* values -> not available (the app still starts; the API answers 503)")
    void notConfigured() {
        assertThat(new CloudinaryEvidenceStorageAdapter(new Cloudinary(Map.of())).isAvailable()).isFalse();
        assertThat(new CloudinaryEvidenceStorageAdapter(new Cloudinary(Map.of("cloud_name", "placeholder-cloud",
                "api_key", "", "api_secret", ""))).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("configured -> available; the signed URL is a private (authenticated) download that expires")
    void signedUrl() {
        CloudinaryEvidenceStorageAdapter adapter = new CloudinaryEvidenceStorageAdapter(new Cloudinary(Map.of(
                "cloud_name", "placeholder-cloud", "api_key", "placeholder-key", "api_secret", "placeholder-value")));
        Instant expiresAt = Instant.parse("2026-11-20T10:05:00Z");

        String url = adapter.signedUrl("disputes/d-1/random-id", "pdf", expiresAt);

        assertThat(adapter.isAvailable()).isTrue();
        assertThat(url).contains("placeholder-cloud").contains("download")
                .contains("expires_at=" + expiresAt.getEpochSecond())
                .contains("type=" + CloudinaryEvidenceStorageAdapter.DELIVERY_TYPE)
                .contains("signature=");
    }

    @Test
    @DisplayName("delete succeeds when cloudinary destroy succeeds")
    void deleteSuccess() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("disputes/d-1/img"), any())).thenReturn(Map.of("result", "ok"));

        CloudinaryEvidenceStorageAdapter adapter = new CloudinaryEvidenceStorageAdapter(cloudinary);
        adapter.delete("disputes/d-1/img");
    }

    @Test
    @DisplayName("delete rethrows IllegalStateException when cloudinary destroy fails")
    void deleteFailureRethrows() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        Uploader uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("disputes/d-1/img"), any())).thenThrow(new IOException("network error"));

        CloudinaryEvidenceStorageAdapter adapter = new CloudinaryEvidenceStorageAdapter(cloudinary);
        Throwable thrown = catchThrowable(() -> adapter.delete("disputes/d-1/img"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disputes/d-1/img")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("listAssets maps publicId and created_at from cloudinary api")
    void listAssetsSuccess() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        com.cloudinary.api.ApiResponse response = mock(com.cloudinary.api.ApiResponse.class);
        when(cloudinary.api()).thenReturn(api);
        when(api.resources(any())).thenReturn(response);
        when(response.get("resources")).thenReturn(java.util.List.of(
                Map.of("public_id", "disputes/d-1/img1", "created_at", "2026-11-26T10:00:00Z")));

        CloudinaryEvidenceStorageAdapter adapter = new CloudinaryEvidenceStorageAdapter(cloudinary);
        java.util.List<EvidenceStoragePort.StorageAsset> assets = adapter.listAssets("disputes/");

        assertThat(assets).containsExactly(
                new EvidenceStoragePort.StorageAsset("disputes/d-1/img1", Instant.parse("2026-11-26T10:00:00Z")));
    }

    @Test
    @DisplayName("listAssets yields null createdAt when cloudinary omits or malforms created_at")
    void listAssetsMissingOrBadCreatedAt() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        com.cloudinary.api.ApiResponse response = mock(com.cloudinary.api.ApiResponse.class);
        when(cloudinary.api()).thenReturn(api);
        when(api.resources(any())).thenReturn(response);
        when(response.get("resources")).thenReturn(java.util.List.of(
                Map.of("public_id", "disputes/d-1/no-date"),
                Map.of("public_id", "disputes/d-1/bad-date", "created_at", "not-a-timestamp")));

        CloudinaryEvidenceStorageAdapter adapter = new CloudinaryEvidenceStorageAdapter(cloudinary);
        java.util.List<EvidenceStoragePort.StorageAsset> assets = adapter.listAssets("disputes/");

        assertThat(assets).extracting(EvidenceStoragePort.StorageAsset::createdAt).containsOnlyNulls();
    }

    @Test
    @DisplayName("listAssets follows next_cursor to retrieve assets beyond the first page")
    void listAssetsFollowsNextCursor() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        when(cloudinary.api()).thenReturn(api);

        com.cloudinary.api.ApiResponse page1 = mock(com.cloudinary.api.ApiResponse.class);
        when(page1.get("resources")).thenReturn(List.of(
                Map.of("public_id", "disputes/d-1/page1-asset", "created_at", "2026-11-26T10:00:00Z")));
        when(page1.get("next_cursor")).thenReturn("cursor-2");

        com.cloudinary.api.ApiResponse page2 = mock(com.cloudinary.api.ApiResponse.class);
        when(page2.get("resources")).thenReturn(List.of(
                Map.of("public_id", "disputes/d-1/page2-orphan", "created_at", "2026-11-26T10:05:00Z")));
        when(page2.get("next_cursor")).thenReturn(null);

        when(api.resources(any())).thenReturn(page1, page2);

        CloudinaryEvidenceStorageAdapter adapter = new CloudinaryEvidenceStorageAdapter(cloudinary);
        List<EvidenceStoragePort.StorageAsset> assets = adapter.listAssets("disputes/");

        // Both pages aggregated, including the orphan on page 2.
        assertThat(assets).extracting(EvidenceStoragePort.StorageAsset::publicId)
                .containsExactly("disputes/d-1/page1-asset", "disputes/d-1/page2-orphan");

        // The adapter made exactly two calls; the second carried the cursor, the first did not.
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(api, times(2)).resources(captor.capture());
        assertThat(captor.getAllValues().get(0).get("next_cursor")).isNull();
        assertThat(captor.getAllValues().get(1).get("next_cursor")).isEqualTo("cursor-2");
    }

    @Test
    @DisplayName("listAssets returns the pages gathered so far when a subsequent page fails (no unsafe state)")
    void listAssetsPaginationStopsOnSubsequentPageFailure() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        when(cloudinary.api()).thenReturn(api);

        com.cloudinary.api.ApiResponse page1 = mock(com.cloudinary.api.ApiResponse.class);
        when(page1.get("resources")).thenReturn(List.of(
                Map.of("public_id", "disputes/d-1/page1-asset", "created_at", "2026-11-26T10:00:00Z")));
        when(page1.get("next_cursor")).thenReturn("cursor-2");

        // First page succeeds, second page throws (e.g. rate limit / transient Admin API error).
        when(api.resources(any())).thenReturn(page1).thenThrow(new RuntimeException("rate limited"));

        CloudinaryEvidenceStorageAdapter adapter = new CloudinaryEvidenceStorageAdapter(cloudinary);
        List<EvidenceStoragePort.StorageAsset> assets = adapter.listAssets("disputes/");

        // Partial result (first page) is returned, no exception propagates; the next scheduled run re-scans.
        assertThat(assets).extracting(EvidenceStoragePort.StorageAsset::publicId)
                .containsExactly("disputes/d-1/page1-asset");
    }
}
