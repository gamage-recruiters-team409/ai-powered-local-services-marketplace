package com.gamagerecruiters.marketplace.modules.provider.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;

/**
 * One resolution rule for profile responses and cross-module read-only facades.
 * No database writes, migration side effects, or booking-blocking decisions.
 */
public final class ProviderServiceAreaResolver {

    private ProviderServiceAreaResolver() {
    }

    public static List<String> resolveDistricts(
            ProviderProfile profile,
            DistrictCatalog districtCatalog
    ) {
        if (profile == null) {
            return List.of();
        }

        if (profile.hasStoredServiceAreaDistricts()) {
            // An explicitly persisted [] stays empty; never fall back.
            Set<String> configured = new LinkedHashSet<>();
            for (String district : profile.getServiceAreaDistricts()) {
                if (district != null && !district.isBlank()) {
                    configured.add(district.trim());
                }
            }
            return List.copyOf(configured);
        }

        // No serviceAreaDistricts field on this older profile. Only then
        // consider its legacy primary district, validating the shared catalog.
        String primaryDistrict = profile.getDistrict();
        if (primaryDistrict == null || primaryDistrict.isBlank()
                || districtCatalog == null) {
            return List.of();
        }

        String normalized = primaryDistrict.trim();
        if (!districtCatalog.isValidDistrict(normalized)) {
            return List.of();
        }

        // Match the exact spelling/capitalization from the shared catalog.
        Set<String> knownDistricts = districtCatalog.getAllDistricts();
        if (knownDistricts != null) {
            for (String knownDistrict : knownDistricts) {
                if (knownDistrict.equalsIgnoreCase(normalized)) {
                    return List.of(knownDistrict);
                }
            }
        }

        // The catalog validated this district even if it does not expose a
        // canonical spelling through getAllDistricts().
        return List.of(normalized);
    }

    public static Set<String> resolveDistrictSet(
            ProviderProfile profile,
            DistrictCatalog districtCatalog
    ) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(resolveDistricts(profile, districtCatalog))
        );
    }
}
