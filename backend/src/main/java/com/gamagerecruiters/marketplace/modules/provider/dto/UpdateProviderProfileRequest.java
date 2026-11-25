
package com.gamagerecruiters.marketplace.modules.provider.dto;

import java.util.List;

import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateProviderProfileRequest {

    private ProviderType providerType;

    @Size(max = 100, message = "Display name must not exceed 100 characters")
    @Pattern(regexp = ".*\\S.*", message = "Display name must not be blank")
    private String displayName;

    @Size(max = 1000, message = "Bio must not exceed 1000 characters")
    private String bio;

    @Size(max = 100, message = "District must not exceed 100 characters")
    @Pattern(regexp = ".*\\S.*", message = "District must not be blank")
    private String district;

    @Size(max = 100, message = "City must not exceed 100 characters")
    @Pattern(regexp = ".*\\S.*", message = "City must not be blank")
    private String city;

    @Size(max = 25, message = "A maximum of 25 service-area districts can be selected")
    private List<@NotBlank(message = "Service-area district cannot be blank") String> serviceAreaDistricts;

    @Min(value = 0, message = "Experience years cannot be negative")
    @Max(value = 80, message = "Experience years must not exceed 80")
    private Integer experienceYears;

    @Size(max = 500, message = "Profile image URL is too long")
    private String profileImageUrl;

    // Null means no category update.
    private List<String> categoryIds;

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

    public List<String> getServiceAreaDistricts() {
        return serviceAreaDistricts;
    }

    public void setServiceAreaDistricts(List<String> serviceAreaDistricts) {
        this.serviceAreaDistricts = serviceAreaDistricts;
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

    public List<String> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<String> categoryIds) {
        this.categoryIds = categoryIds;
    }
}
