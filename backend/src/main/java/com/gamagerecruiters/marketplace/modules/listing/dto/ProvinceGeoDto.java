package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.util.List;

/**
 * Sri Lankan province grouping canonical districts and Leaflet.js center coordinates.
 */
public record ProvinceGeoDto(
        String name,
        List<DistrictGeoDto> districts
) {
    public ProvinceGeoDto {
        districts = districts == null ? List.of() : List.copyOf(districts);
    }
}

