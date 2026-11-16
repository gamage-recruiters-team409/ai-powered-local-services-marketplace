package com.gamagerecruiters.marketplace.modules.provider.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;

public class ProviderProfileResponse {

    private String id;
    private String userId;
    private ProviderType providerType;
    private String displayName;
    private String bio;
    private String district;
    private String city;
    private Integer experienceYears;
    private String profileImageUrl;
    private Instant createdAt;
    private Instant updatedAt;

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
}