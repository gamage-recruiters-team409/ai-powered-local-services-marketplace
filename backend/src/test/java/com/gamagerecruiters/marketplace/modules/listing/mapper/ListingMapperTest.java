package com.gamagerecruiters.marketplace.modules.listing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import com.gamagerecruiters.marketplace.modules.listing.domain.AddOnItem;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort.CategorySnapshot;

class ListingMapperTest {

    private ListingMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ListingMapper();
    }

    private ServiceListing activeListing() {
        ServiceListing listing = new ServiceListing();
        listing.setId("listing-1");
        listing.setProviderId("provider-1");
        listing.setCategoryId("cat-sub");
        listing.setTitle("Residential Wiring Inspection");
        listing.setDescription("Complete home breaker panel fault check and safety certification.");
        listing.setPricingModel(PricingModel.FIXED);
        listing.setBasePrice(new BigDecimal("3500.00"));
        listing.setDurationMin(60);
        listing.setExecutionModel(ExecutionModel.ON_SITE);
        listing.setDistrict("Colombo");
        listing.setBaseCity("Dehiwala");
        listing.setLocation(new GeoJsonPoint(79.8612, 6.9271));
        listing.setCoverageRadiusKm(15);
        listing.setServiceAreas(List.of("Dehiwala"));
        listing.setImages(List.of("https://cdn.example.com/1.jpg"));
        listing.setCoverImageUrl("https://cdn.example.com/1.jpg");
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setCreatedAt(Instant.now());
        listing.setUpdatedAt(Instant.now());
        return listing;
    }

    @Test
    @DisplayName("Null or unresolved category fails closed in both full and summary DTO mappings")
    void nullCategoryMarksCategoryInactiveAndNotBookable() {
        ServiceListing active = activeListing();

        ListingResponse full = mapper.toResponse(active, null, false);
        assertThat(full.categoryActive()).isFalse();
        assertThat(full.availableForBooking()).isFalse();
        assertThat(full.categoryName()).isNull();

        ListingSummaryResponse summary = mapper.toSummaryResponse(active, null, false);
        assertThat(summary.categoryActive()).isFalse();
        assertThat(summary.availableForBooking()).isFalse();
        assertThat(summary.categoryName()).isNull();
    }

    @Test
    @DisplayName("Inactive category marks categoryActive=false and availableForBooking=false in both DTOs")
    void inactiveCategoryMarksUnavailable() {
        ServiceListing active = activeListing();
        CategorySnapshot inactiveCat = new CategorySnapshot("cat-sub", "Wiring", "cat-root", false, new BigDecimal("0.10"));

        ListingResponse full = mapper.toResponse(active, inactiveCat, false);
        assertThat(full.categoryActive()).isFalse();
        assertThat(full.availableForBooking()).isFalse();

        ListingSummaryResponse summary = mapper.toSummaryResponse(active, inactiveCat, false);
        assertThat(summary.categoryActive()).isFalse();
        assertThat(summary.availableForBooking()).isFalse();
    }

    @Test
    @DisplayName("Active category yields availableForBooking=true only when listing is ACTIVE and not suspended")
    void activeCategoryRespectsListingAvailability() {
        ServiceListing active = activeListing();
        CategorySnapshot activeCat = new CategorySnapshot("cat-sub", "Wiring", "cat-root", true, new BigDecimal("0.10"));

        ListingResponse full = mapper.toResponse(active, activeCat, false);
        assertThat(full.categoryActive()).isTrue();
        assertThat(full.availableForBooking()).isTrue();

        ListingSummaryResponse summary = mapper.toSummaryResponse(active, activeCat, false);
        assertThat(summary.categoryActive()).isTrue();
        assertThat(summary.availableForBooking()).isTrue();

        active.setHiddenByProviderSuspension(true);
        assertThat(mapper.toResponse(active, activeCat, false).availableForBooking()).isFalse();
        assertThat(mapper.toSummaryResponse(active, activeCat, false).availableForBooking()).isFalse();
    }

    @Test
    @DisplayName("Public view hides inactive add-ons, moderation metadata, and optimistic version while owner view includes them")
    void publicMappingFiltersInactiveAddOnsAndInternalFields() {
        ServiceListing listing = activeListing();
        listing.setAddOns(List.of(
                new AddOnItem("a-1", "Active Extra", new BigDecimal("500.00"), 15, true),
                new AddOnItem("a-2", "Disabled Extra", new BigDecimal("900.00"), 30, false)
        ));
        listing.setModerationFlags(List.of("INTERNAL_NOTE"));
        listing.setModerationReason("Audit log");
        listing.setModeratedAt(Instant.now());
        listing.setVersion(4L);
        CategorySnapshot activeCat = new CategorySnapshot("cat-sub", "Wiring", "cat-root", true, new BigDecimal("0.10"));

        ListingResponse publicDto = mapper.toResponse(listing, activeCat, false);
        assertThat(publicDto.addOns()).hasSize(1);
        assertThat(publicDto.addOns().get(0).addOnId()).isEqualTo("a-1");
        assertThat(publicDto.activeAddOnCount()).isEqualTo(1);
        assertThat(publicDto.moderationFlags()).isEmpty();
        assertThat(publicDto.moderationReason()).isNull();
        assertThat(publicDto.moderatedAt()).isNull();
        assertThat(publicDto.version()).isNull();

        ListingResponse internalDto = mapper.toResponse(listing, activeCat, true);
        assertThat(internalDto.addOns()).hasSize(2);
        assertThat(internalDto.moderationFlags()).containsExactly("INTERNAL_NOTE");
        assertThat(internalDto.moderationReason()).isEqualTo("Audit log");
        assertThat(internalDto.moderatedAt()).isNotNull();
        assertThat(internalDto.version()).isEqualTo(4L);
    }
}
