package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.modules.customer.service.StaticDistrictCatalog;
import com.gamagerecruiters.marketplace.modules.listing.dto.DistrictGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.GeoPointDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProvinceGeoDto;

class SriLankaDistrictGeoServiceTest {

    private SriLankaDistrictGeoService geoService;

    @BeforeEach
    void setUp() {
        StaticDistrictCatalog catalog = new StaticDistrictCatalog(new ObjectMapper());
        catalog.init();
        geoService = new SriLankaDistrictGeoService(catalog);
    }

    @Test
    @DisplayName("All 9 provinces and 25 Sri Lankan districts carry valid Leaflet center coordinates")
    void all25DistrictsHaveValidSriLankaCoordinates() {
        List<ProvinceGeoDto> provinces = geoService.getProvincesWithCoordinates();

        assertThat(provinces).hasSize(9);
        List<DistrictGeoDto> allDistricts = provinces.stream()
                .flatMap(p -> p.districts().stream())
                .toList();
        assertThat(allDistricts).hasSize(25);

        for (DistrictGeoDto dto : allDistricts) {
            GeoPointDto point = new GeoPointDto(dto.longitude(), dto.latitude());
            assertThat(geoService.isWithinSriLankaBounds(point))
                    .as("District %s coordinates (%s, %s)", dto.name(), dto.latitude(), dto.longitude())
                    .isTrue();
            assertThat(dto.defaultZoom()).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    @DisplayName("Normalizes UI district labels and rejects unknown districts")
    void normalizesDistrictLabels() {
        assertThat(geoService.resolveCanonicalDistrict("Colombo")).contains("Colombo");
        assertThat(geoService.resolveCanonicalDistrict("colombo district")).contains("Colombo");
        assertThat(geoService.resolveCanonicalDistrict("Colombo District (Western Province)")).contains("Colombo");
        assertThat(geoService.resolveCanonicalDistrict("Nuwara Eliya District (Central Province)"))
                .contains("Nuwara Eliya");
        assertThat(geoService.resolveCanonicalDistrict("Atlantis")).isEmpty();
        assertThat(geoService.resolveCanonicalDistrict("  ")).isEmpty();
    }

    @Test
    @DisplayName("Validates Sri Lanka coordinate bounding box and defaults district center")
    void validatesSriLankaBounds() {
        GeoPointDto colomboCenter = geoService.getDistrictCenter("Colombo");
        assertThat(colomboCenter.longitude()).isCloseTo(79.8612, org.assertj.core.data.Offset.offset(0.01));
        assertThat(colomboCenter.latitude()).isCloseTo(6.9271, org.assertj.core.data.Offset.offset(0.01));

        assertThat(geoService.isWithinSriLankaBounds(colomboCenter)).isTrue();
        assertThat(geoService.isWithinSriLankaBounds(new GeoPointDto(0.0, 0.0))).isFalse();
        assertThat(geoService.isWithinSriLankaBounds(new GeoPointDto(77.5946, 12.9716))).isFalse();
    }
}

