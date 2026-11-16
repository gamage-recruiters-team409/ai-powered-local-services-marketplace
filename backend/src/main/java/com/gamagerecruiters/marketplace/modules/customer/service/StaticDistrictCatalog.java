package com.gamagerecruiters.marketplace.modules.customer.service;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.modules.customer.dto.ProvinceDto;

import jakarta.annotation.PostConstruct;

@Component
public class StaticDistrictCatalog implements DistrictCatalog {

    private static final Logger log = LoggerFactory.getLogger(StaticDistrictCatalog.class);
    private static final String RESOURCE_PATH = "data/sri-lanka-districts.json";
    private static final int EXPECTED_DISTRICT_COUNT = 25;
    private static final int EXPECTED_PROVINCE_COUNT = 9;

    private final ObjectMapper objectMapper;
    private List<ProvinceDto> provinces = Collections.emptyList();
    private Set<String> allDistricts = Collections.emptySet();

    public StaticDistrictCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadAndValidateDistricts(RESOURCE_PATH);
    }

    public void loadAndValidateDistricts(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new IllegalStateException("District catalog resource not found at classpath:" + path);
            }

            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode provincesNode = root.get("provinces");
                if (provincesNode == null || !provincesNode.isArray()) {
                    throw new IllegalStateException("District catalog root must contain an array property 'provinces'");
                }

                List<ProvinceDto> loadedProvinces = new java.util.ArrayList<>();
                Set<String> districtSet = new HashSet<>();

                for (JsonNode pNode : provincesNode) {
                    String provinceName = pNode.get("name").asText();
                    List<String> dList = new java.util.ArrayList<>();
                    JsonNode districtsNode = pNode.get("districts");
                    if (districtsNode != null && districtsNode.isArray()) {
                        for (JsonNode d : districtsNode) {
                            String districtName = d.asText().trim();
                            dList.add(districtName);
                            if (!districtSet.add(districtName)) {
                                throw new IllegalStateException("Duplicate district found in catalog: " + districtName);
                            }
                        }
                    }
                    loadedProvinces.add(new ProvinceDto(provinceName, Collections.unmodifiableList(dList)));
                }

                if (districtSet.size() != EXPECTED_DISTRICT_COUNT) {
                    throw new IllegalStateException(
                            "Startup failed: Sri Lanka district catalog must contain exactly "
                                    + EXPECTED_DISTRICT_COUNT + " districts, but found " + districtSet.size()
                    );
                }

                if (loadedProvinces.size() != EXPECTED_PROVINCE_COUNT) {
                    throw new IllegalStateException(
                            "Startup failed: Sri Lanka district catalog must contain exactly "
                                    + EXPECTED_PROVINCE_COUNT + " provinces, but found " + loadedProvinces.size()
                    );
                }

                this.provinces = Collections.unmodifiableList(loadedProvinces);
                this.allDistricts = Collections.unmodifiableSet(districtSet);
                log.info("Loaded {} districts across {} provinces from classpath:{}", districtSet.size(), loadedProvinces.size(), path);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load and validate district catalog from classpath:" + path, e);
        }
    }

    @Override
    public List<ProvinceDto> getProvinces() {
        return provinces;
    }

    @Override
    public Set<String> getAllDistricts() {
        return allDistricts;
    }

    @Override
    public boolean isValidDistrict(String district) {
        if (district == null || district.isBlank()) {
            return false;
        }
        return allDistricts.stream().anyMatch(d -> d.equalsIgnoreCase(district.trim()));
    }
}
