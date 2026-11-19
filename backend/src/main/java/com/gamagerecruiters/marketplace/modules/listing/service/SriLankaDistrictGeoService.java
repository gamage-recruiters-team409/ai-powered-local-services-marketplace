package com.gamagerecruiters.marketplace.modules.listing.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.customer.dto.ProvinceDto;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.listing.dto.DistrictGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.GeoPointDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProvinceGeoDto;

/**
 * Provides canonical center coordinates for all 25 Sri Lankan districts aligned with
 * {@link DistrictCatalog} (sri-lanka-districts.json) for Leaflet.js maps and Sri Lanka
 * coordinate boundary validation.
 */
@Service
public class SriLankaDistrictGeoService {

    public static final double MIN_LONGITUDE = 79.50;
    public static final double MAX_LONGITUDE = 81.95;
    public static final double MIN_LATITUDE = 5.85;
    public static final double MAX_LATITUDE = 9.90;
    private static final int DEFAULT_DISTRICT_ZOOM = 12;

    private static final Map<String, double[]> DISTRICT_CENTERS = Map.ofEntries(
            // Western
            Map.entry("Colombo", new double[] { 6.9271, 79.8612 }),
            Map.entry("Gampaha", new double[] { 7.0873, 80.0144 }),
            Map.entry("Kalutara", new double[] { 6.5854, 79.9607 }),
            // Central
            Map.entry("Kandy", new double[] { 7.2906, 80.6337 }),
            Map.entry("Matale", new double[] { 7.4675, 80.6234 }),
            Map.entry("Nuwara Eliya", new double[] { 6.9497, 80.7891 }),
            // Southern
            Map.entry("Galle", new double[] { 6.0535, 80.2210 }),
            Map.entry("Matara", new double[] { 5.9549, 80.5550 }),
            Map.entry("Hambantota", new double[] { 6.1241, 81.1185 }),
            // Northern
            Map.entry("Jaffna", new double[] { 9.6615, 80.0255 }),
            Map.entry("Kilinochchi", new double[] { 9.3803, 80.3770 }),
            Map.entry("Mannar", new double[] { 8.9810, 79.9044 }),
            Map.entry("Vavuniya", new double[] { 8.7514, 80.4971 }),
            Map.entry("Mullaitivu", new double[] { 9.2671, 80.8142 }),
            // Eastern
            Map.entry("Batticaloa", new double[] { 7.7310, 81.6747 }),
            Map.entry("Ampara", new double[] { 7.2975, 81.6820 }),
            Map.entry("Trincomalee", new double[] { 8.5874, 81.2152 }),
            // North Western
            Map.entry("Kurunegala", new double[] { 7.4863, 80.3623 }),
            Map.entry("Puttalam", new double[] { 8.0362, 79.8283 }),
            // North Central
            Map.entry("Anuradhapura", new double[] { 8.3114, 80.4037 }),
            Map.entry("Polonnaruwa", new double[] { 7.9403, 81.0188 }),
            // Uva
            Map.entry("Badulla", new double[] { 6.9934, 81.0550 }),
            Map.entry("Monaragala", new double[] { 6.8728, 81.3507 }),
            // Sabaragamuwa
            Map.entry("Ratnapura", new double[] { 6.6828, 80.3992 }),
            Map.entry("Kegalle", new double[] { 7.2513, 80.3464 })
    );

    private final DistrictCatalog districtCatalog;

    public SriLankaDistrictGeoService(DistrictCatalog districtCatalog) {
        this.districtCatalog = districtCatalog;
    }

    public List<ProvinceGeoDto> getProvincesWithCoordinates() {
        List<ProvinceGeoDto> result = new ArrayList<>();
        for (ProvinceDto province : districtCatalog.getProvinces()) {
            List<DistrictGeoDto> districts = new ArrayList<>();
            for (String districtName : province.districts()) {
                double[] coords = DISTRICT_CENTERS.get(districtName);
                if (coords == null) {
                    throw new IllegalStateException("Missing center coordinates for Sri Lankan district: " + districtName);
                }
                districts.add(new DistrictGeoDto(
                        districtName,
                        province.name(),
                        coords[0],
                        coords[1],
                        DEFAULT_DISTRICT_ZOOM
                ));
            }
            result.add(new ProvinceGeoDto(province.name(), districts));
        }
        return List.copyOf(result);
    }

    /**
     * Normalizes UI labels like {@code "Colombo District (Western Province)"} or {@code "colombo"}
     * into the canonical district name defined in {@link DistrictCatalog}.
     */
    public Optional<String> resolveCanonicalDistrict(String rawDistrict) {
        if (rawDistrict == null || rawDistrict.isBlank()) {
            return Optional.empty();
        }

        String candidate = rawDistrict.trim();
        int parenIdx = candidate.indexOf('(');
        if (parenIdx >= 0) {
            candidate = candidate.substring(0, parenIdx).trim();
        }
        if (candidate.toLowerCase(Locale.ROOT).endsWith(" district")) {
            candidate = candidate.substring(0, candidate.length() - " district".length()).trim();
        }

        if (candidate.isEmpty() || !districtCatalog.isValidDistrict(candidate)) {
            return Optional.empty();
        }

        String finalCandidate = candidate;
        return districtCatalog.getAllDistricts().stream()
                .filter(d -> d.equalsIgnoreCase(finalCandidate))
                .findFirst()
                .or(() -> Optional.of(finalCandidate));
    }

    public GeoPointDto getDistrictCenter(String canonicalDistrict) {
        double[] coords = resolveCanonicalDistrict(canonicalDistrict)
                .map(DISTRICT_CENTERS::get)
                .orElseGet(() -> DISTRICT_CENTERS.get(canonicalDistrict));
        if (coords == null) {
            throw new IllegalArgumentException("Unknown Sri Lankan district: " + canonicalDistrict);
        }
        return new GeoPointDto(coords[1], coords[0]);
    }

    public boolean isWithinSriLankaBounds(GeoPointDto point) {
        if (point == null || point.longitude() == null || point.latitude() == null) {
            return false;
        }
        double lng = point.longitude();
        double lat = point.latitude();
        return lng >= MIN_LONGITUDE && lng <= MAX_LONGITUDE
                && lat >= MIN_LATITUDE && lat <= MAX_LATITUDE;
    }
}

