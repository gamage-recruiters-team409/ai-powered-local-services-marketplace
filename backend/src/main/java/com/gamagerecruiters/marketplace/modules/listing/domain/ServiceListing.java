package com.gamagerecruiters.marketplace.modules.listing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document representing a provider's service listing in the {@code service_listings} collection.
 */
@Document(collection = "service_listings")
public class ServiceListing {

    @Id
    private String id;

    /** Authenticated provider User ID matching CurrentUser.getUserId(). */
    private String providerId;

    private String categoryId;

    private String title;
    private String description;
    private PricingModel pricingModel;
    private BigDecimal basePrice;
    private Integer durationMin;
    private ExecutionModel executionModel;

    private String district;
    private String baseCity;
    private GeoJsonPoint location;
    private Integer coverageRadiusKm;
    private List<String> serviceAreas = new ArrayList<>();

    private List<String> images = new ArrayList<>();
    private String coverImageUrl;
    private List<AddOnItem> addOns = new ArrayList<>();

    private ListingStatus status = ListingStatus.DRAFT;
    private boolean hiddenByProviderSuspension;
    private List<String> moderationFlags = new ArrayList<>();
    private String moderationReason;
    private String moderatedBy;
    private Instant moderatedAt;

    private double ratingAvg;
    private int ratingCount;
    private List<Double> embedding;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    public ServiceListing() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PricingModel getPricingModel() {
        return pricingModel;
    }

    public void setPricingModel(PricingModel pricingModel) {
        this.pricingModel = pricingModel;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public Integer getDurationMin() {
        return durationMin;
    }

    public void setDurationMin(Integer durationMin) {
        this.durationMin = durationMin;
    }

    public ExecutionModel getExecutionModel() {
        return executionModel;
    }

    public void setExecutionModel(ExecutionModel executionModel) {
        this.executionModel = executionModel;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getBaseCity() {
        return baseCity;
    }

    public void setBaseCity(String baseCity) {
        this.baseCity = baseCity;
    }

    public GeoJsonPoint getLocation() {
        return location;
    }

    public void setLocation(GeoJsonPoint location) {
        this.location = location;
    }

    public Integer getCoverageRadiusKm() {
        return coverageRadiusKm;
    }

    public void setCoverageRadiusKm(Integer coverageRadiusKm) {
        this.coverageRadiusKm = coverageRadiusKm;
    }

    public List<String> getServiceAreas() {
        return serviceAreas;
    }

    public void setServiceAreas(List<String> serviceAreas) {
        this.serviceAreas = serviceAreas == null ? new ArrayList<>() : new ArrayList<>(serviceAreas);
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images == null ? new ArrayList<>() : new ArrayList<>(images);
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public List<AddOnItem> getAddOns() {
        return addOns;
    }

    public void setAddOns(List<AddOnItem> addOns) {
        this.addOns = addOns == null ? new ArrayList<>() : new ArrayList<>(addOns);
    }

    public ListingStatus getStatus() {
        return status;
    }

    public void setStatus(ListingStatus status) {
        this.status = status;
    }

    public boolean isHiddenByProviderSuspension() {
        return hiddenByProviderSuspension;
    }

    public void setHiddenByProviderSuspension(boolean hiddenByProviderSuspension) {
        this.hiddenByProviderSuspension = hiddenByProviderSuspension;
    }

    public List<String> getModerationFlags() {
        return moderationFlags;
    }

    public void setModerationFlags(List<String> moderationFlags) {
        this.moderationFlags = moderationFlags == null ? new ArrayList<>() : new ArrayList<>(moderationFlags);
    }

    public String getModerationReason() {
        return moderationReason;
    }

    public void setModerationReason(String moderationReason) {
        this.moderationReason = moderationReason;
    }

    public String getModeratedBy() {
        return moderatedBy;
    }

    public void setModeratedBy(String moderatedBy) {
        this.moderatedBy = moderatedBy;
    }

    public Instant getModeratedAt() {
        return moderatedAt;
    }

    public void setModeratedAt(Instant moderatedAt) {
        this.moderatedAt = moderatedAt;
    }

    public double getRatingAvg() {
        return ratingAvg;
    }

    public void setRatingAvg(double ratingAvg) {
        this.ratingAvg = ratingAvg;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(int ratingCount) {
        this.ratingCount = ratingCount;
    }

    public List<Double> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Double> embedding) {
        this.embedding = embedding == null ? null : new ArrayList<>(embedding);
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
