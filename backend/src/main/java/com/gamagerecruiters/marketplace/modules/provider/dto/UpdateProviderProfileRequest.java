package com.gamagerecruiters.marketplace.modules.provider.dto;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class UpdateProviderProfileRequest {

    private ProviderType providerType;

    @Size(max = 100, message = "Display name must not exceed 100 characters")
    private String displayName;

    @Size(max = 1000, message = "Bio must not exceed 1000 characters")
    private String bio;

    private String district;

    private String city;

    @Min(value = 0, message = "Experience years cannot be negative")
    @Max(value = 80, message = "Experience years must not exceed 80")
    private Integer experienceYears;

    @Size(max = 500, message = "Profile image URL is too long")
    private String profileImageUrl;

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Integer getExperienceYears() {
        return experienceYears;
    }

    public void setExperienceYears(Integer experienceYears) {
        this.experienceYears = experienceYears;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public void setProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }
}