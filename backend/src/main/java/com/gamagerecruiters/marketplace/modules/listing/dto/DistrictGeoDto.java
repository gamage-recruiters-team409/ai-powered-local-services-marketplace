package com.gamagerecruiters.marketplace.modules.listing.dto;

/**
 * Canonical Sri Lankan district metadata with Leaflet.js center coordinates.
 */
public record DistrictGeoDto(
        String name,
        String province,
        double latitude,
        double longitude,
        int defaultZoom
) {
}

