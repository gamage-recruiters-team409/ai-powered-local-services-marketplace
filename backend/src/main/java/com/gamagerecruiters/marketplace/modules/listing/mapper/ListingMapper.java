package com.gamagerecruiters.marketplace.modules.listing.mapper;

import java.util.List;

import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.listing.domain.AddOnItem;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;
import com.gamagerecruiters.marketplace.modules.listing.dto.AddOnItemResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.GeoPointDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort.CategorySnapshot;

/**
 * Maps between {@link ServiceListing} domain documents and external DTO records.
 * GeoJSON coordinates follow the {@code [longitude, latitude]} axis order.
 */
@Component
public class ListingMapper {

    public GeoJsonPoint toGeoJsonPoint(GeoPointDto dto) {
        if (dto == null) {
            return null;
        }
        return new GeoJsonPoint(dto.longitude(), dto.latitude());
    }

    public GeoPointDto toGeoPointDto(GeoJsonPoint point) {
        if (point == null) {
            return null;
        }
        return new GeoPointDto(point.getX(), point.getY());
    }

    public AddOnItemResponse toAddOnItemResponse(AddOnItem item) {
        if (item == null) {
            return null;
        }
        return new AddOnItemResponse(
                item.getAddOnId(),
                item.getTitle(),
                item.getDescription(),
                item.getExtraPrice(),
                item.getExtraDurationMin(),
                item.isActive()
        );
    }

    public ListingResponse toResponse(
            ServiceListing listing,
            CategorySnapshot category,
            boolean includeInternalDetails
    ) {
        List<AddOnItemResponse> addOnResponses = listing.getAddOns() == null
                ? List.of()
                : listing.getAddOns().stream()
                        .filter(item -> includeInternalDetails || item.isActive())
                        .map(this::toAddOnItemResponse)
                        .toList();

        boolean categoryActive = isCategoryActive(category);
        int activeAddOnCount = countActiveAddOns(listing);
        boolean availableForBooking = isAvailableForBooking(listing, categoryActive);

        return new ListingResponse(
                listing.getId(),
                listing.getProviderId(),
                listing.getCategoryId(),
                category != null ? category.name() : null,
                category != null ? category.parentId() : null,
                listing.getTitle(),
                listing.getDescription(),
                listing.getPricingModel(),
                listing.getBasePrice(),
                listing.getDurationMin(),
                listing.getExecutionModel(),
                listing.getDistrict(),
                listing.getBaseCity(),
                toGeoPointDto(listing.getLocation()),
                listing.getCoverageRadiusKm(),
                listing.getServiceAreas(),
                listing.getImages(),
                listing.getCoverImageUrl(),
                addOnResponses,
                listing.getStatus(),
                availableForBooking,
                categoryActive,
                activeAddOnCount,
                includeInternalDetails ? listing.getModerationFlags() : List.of(),
                includeInternalDetails ? listing.getModerationReason() : null,
                includeInternalDetails ? listing.getModeratedAt() : null,
                listing.getRatingAvg(),
                listing.getRatingCount(),
                includeInternalDetails ? listing.getVersion() : null,
                listing.getCreatedAt(),
                listing.getUpdatedAt()
        );
    }

    public ListingSummaryResponse toSummaryResponse(
            ServiceListing listing,
            CategorySnapshot category,
            boolean includeModerationDetails
    ) {
        boolean categoryActive = isCategoryActive(category);
        int activeAddOnCount = countActiveAddOns(listing);
        boolean availableForBooking = isAvailableForBooking(listing, categoryActive);

        return new ListingSummaryResponse(
                listing.getId(),
                listing.getProviderId(),
                listing.getCategoryId(),
                category != null ? category.name() : null,
                listing.getTitle(),
                listing.getPricingModel(),
                listing.getBasePrice(),
                listing.getDurationMin(),
                listing.getExecutionModel(),
                listing.getDistrict(),
                listing.getBaseCity(),
                listing.getCoverImageUrl(),
                listing.getStatus(),
                availableForBooking,
                categoryActive,
                activeAddOnCount,
                includeModerationDetails ? listing.getModerationFlags() : List.of(),
                listing.getRatingAvg(),
                listing.getRatingCount(),
                listing.getCreatedAt(),
                listing.getUpdatedAt()
        );
    }

    private boolean isCategoryActive(CategorySnapshot category) {
        return category != null && category.active();
    }

    private boolean isAvailableForBooking(ServiceListing listing, boolean categoryActive) {
        return listing.getStatus() == ListingStatus.ACTIVE
                && !listing.isHiddenByProviderSuspension()
                && categoryActive;
    }

    private int countActiveAddOns(ServiceListing listing) {
        if (listing.getAddOns() == null || listing.getAddOns().isEmpty()) {
            return 0;
        }
        return (int) listing.getAddOns().stream().filter(AddOnItem::isActive).count();
    }
}
