package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class DistrictCatalogTest {

    private StaticDistrictCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new StaticDistrictCatalog(new ObjectMapper());
        catalog.init();
    }

    @Test
    @DisplayName("District catalog must contain exactly 25 districts across 9 provinces")
    void shouldLoadExactly25DistrictsIn9Provinces() {
        assertEquals(9, catalog.getProvinces().size(), "Must have exactly 9 provinces");
        assertEquals(25, catalog.getAllDistricts().size(), "Must have exactly 25 districts");

        // Verify key districts across provinces
        assertTrue(catalog.isValidDistrict("Colombo"));
        assertTrue(catalog.isValidDistrict("colombo")); // Case insensitive
        assertTrue(catalog.isValidDistrict("Kandy"));
        assertTrue(catalog.isValidDistrict("Galle"));
        assertTrue(catalog.isValidDistrict("Jaffna"));
        assertTrue(catalog.isValidDistrict("Batticaloa"));
        assertTrue(catalog.isValidDistrict("Kurunegala"));
        assertTrue(catalog.isValidDistrict("Anuradhapura"));
        assertTrue(catalog.isValidDistrict("Badulla"));
        assertTrue(catalog.isValidDistrict("Ratnapura"));

        assertFalse(catalog.isValidDistrict("InvalidDistrict"));
        assertFalse(catalog.isValidDistrict(""));
        assertFalse(catalog.isValidDistrict(null));
    }

    @Test
    @DisplayName("District catalog must fail startup if resource file is missing")
    void shouldFailStartupWhenResourceMissing() {
        StaticDistrictCatalog missingCatalog = new StaticDistrictCatalog(new ObjectMapper());
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> missingCatalog.loadAndValidateDistricts("data/non-existent-districts.json")
        );
        assertTrue(ex.getMessage().contains("District catalog resource not found"));
    }
}
