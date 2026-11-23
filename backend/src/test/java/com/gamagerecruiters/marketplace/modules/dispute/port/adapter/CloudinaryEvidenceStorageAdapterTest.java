package com.gamagerecruiters.marketplace.modules.dispute.port.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cloudinary.Cloudinary;

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
}
