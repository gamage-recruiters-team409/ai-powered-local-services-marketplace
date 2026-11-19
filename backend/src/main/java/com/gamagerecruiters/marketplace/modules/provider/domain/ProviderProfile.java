
package com.gamagerecruiters.marketplace.modules.provider.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Document(collection = "provider_profiles")
public class ProviderProfile {

    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private ProviderType providerType;

    private String displayName;

    private String bio;

    private String district;

    private String city;

    // Selected service-area districts from the shared Sri Lankan district catalog.
    private List<String> serviceAreaDistricts = new ArrayList<>();

    // Provider voluntary activity status.
    // Existing MongoDB profiles without this field are treated as ACTIVE.
    private ProviderProfileActivityStatus activityStatus =
            ProviderProfileActivityStatus.ACTIVE;

    private Integer experienceYears;

    // Public HTTPS URL used to display the profile image.
    private String profileImageUrl;

    // Cloudinary public ID used to replace or delete the image.
    private String profileImagePublicId;

    // Selected service category IDs from the Catalog module.
    private List<String> categoryIds = new ArrayList<>();

    /*
     * Provider payout details.
     *
     * The account number is stored only as AES-GCM ciphertext.
     * These fields are deliberately excluded from direct JSON serialization.
     */
    private String payoutBankName;

    private String payoutAccountHolderName;

    private String payoutAccountNumberEncrypted;

    private Instant createdAt;

    private Instant updatedAt;

    public ProviderProfile() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

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
        return serviceAreaDistricts == null
                ? List.of()
                : List.copyOf(serviceAreaDistricts);
    }

    public void setServiceAreaDistricts(
            List<String> serviceAreaDistricts
    ) {
        this.serviceAreaDistricts = serviceAreaDistricts == null
                ? new ArrayList<>()
                : new ArrayList<>(serviceAreaDistricts);
    }

    @JsonIgnore
    public ProviderProfileActivityStatus getActivityStatus() {
        return activityStatus == null
                ? ProviderProfileActivityStatus.ACTIVE
                : activityStatus;
    }

    public void setActivityStatus(
            ProviderProfileActivityStatus activityStatus
    ) {
        this.activityStatus = activityStatus;
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

    public String getProfileImagePublicId() {
        return profileImagePublicId;
    }

    public void setProfileImagePublicId(String profileImagePublicId) {
        this.profileImagePublicId = profileImagePublicId;
    }

    public List<String> getCategoryIds() {
        return categoryIds == null
                ? List.of()
                : List.copyOf(categoryIds);
    }

    public void setCategoryIds(List<String> categoryIds) {
        this.categoryIds = categoryIds == null
                ? new ArrayList<>()
                : new ArrayList<>(categoryIds);
    }

    @JsonIgnore
    public String getPayoutBankName() {
        return payoutBankName;
    }

    public void setPayoutBankName(String payoutBankName) {
        this.payoutBankName = payoutBankName;
    }

    @JsonIgnore
    public String getPayoutAccountHolderName() {
        return payoutAccountHolderName;
    }

    public void setPayoutAccountHolderName(
            String payoutAccountHolderName
    ) {
        this.payoutAccountHolderName = payoutAccountHolderName;
    }

    @JsonIgnore
    public String getPayoutAccountNumberEncrypted() {
        return payoutAccountNumberEncrypted;
    }

    public void setPayoutAccountNumberEncrypted(
            String payoutAccountNumberEncrypted
    ) {
        this.payoutAccountNumberEncrypted =
                payoutAccountNumberEncrypted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
