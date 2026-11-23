
package com.gamagerecruiters.marketplace.modules.provider.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;

public class ProviderProfileResponse {

    private String id;
    private String userId;
    private ProviderType providerType;
    private String displayName;
    private String bio;
    private String district;
    private String city;
    private List<String> serviceAreaDistricts = List.of();
    private Integer experienceYears;
    private String profileImageUrl;
    private Instant createdAt;
    private Instant updatedAt;

    private List<String> categoryIds = List.of();

    public ProviderProfileResponse() {
    }

    public ProviderProfileResponse(
            String id,
            String userId,
            ProviderType providerType,
            String displayName,
            String bio,
            String district,
            String city,
            Integer experienceYears,
            String profileImageUrl,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.userId = userId;
        this.providerType = providerType;
        this.displayName = displayName;
        this.bio = bio;
        this.district = district;
        this.city = city;
        this.experienceYears = experienceYears;
        this.profileImageUrl = profileImageUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getDistrict() {
        return district;
    }

    public String getCity() {
        return city;
    }

    public List<String> getServiceAreaDistricts() {
        return serviceAreaDistricts;
    }

    public void setServiceAreaDistricts(List<String> serviceAreaDistricts) {
        this.serviceAreaDistricts = serviceAreaDistricts == null
                ? List.of()
                : List.copyOf(serviceAreaDistricts);
    }

    public Integer getExperienceYears() {
        return experienceYears;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<String> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<String> categoryIds) {
        this.categoryIds = categoryIds == null
                ? List.of()
                : List.copyOf(categoryIds);
    }
}
