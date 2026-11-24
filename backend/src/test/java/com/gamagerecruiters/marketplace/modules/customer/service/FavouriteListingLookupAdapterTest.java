package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

@ExtendWith(MockitoExtension.class)
class FavouriteListingLookupAdapterTest {

    @Mock
    private ListingService listingService;

    private FavouriteListingLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FavouriteListingLookupAdapter(listingService);
    }

    private ListingResponse createMockListing(
            String id,
            String title,
            String category,
            String city,
            String district,
            BigDecimal price,
            PricingModel pricingModel,
            String imageUrl,
            double ratingAvg,
            int ratingCount,
            ListingStatus status,
            boolean availableForBooking
    ) {
        return new ListingResponse(
                id,
                "prov-100",
                "cat-10",
                category,
                null,
                title,
                "Detailed description of listing",
                pricingModel,
                price,
                60,
                ExecutionModel.ON_SITE,
                district,
                city,
                null,
                25,
                List.of(),
                List.of(),
                imageUrl,
                List.of(),
                status,
                availableForBooking,
                true,
                0,
                List.of(),
                null,
                null,
                ratingAvg,
                ratingCount,
                1L,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("lookupBatch: returns empty map when input collection is empty")
    void lookupBatch_emptyInput_returnsEmptyMap() {
        Map<String, FavouriteTargetDetails> result = adapter.lookupBatch(Collections.emptyList());

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(listingService, never()).getListingsByIds(any());
    }

    @Test
    @DisplayName("lookupBatch: returns empty map when input collection is null")
    void lookupBatch_nullInput_returnsEmptyMap() {
        Map<String, FavouriteTargetDetails> result = adapter.lookupBatch(null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(listingService, never()).getListingsByIds(any());
    }

    @Test
    @DisplayName("lookup: returns empty when targetId is null or blank")
    void lookup_nullOrBlankId_returnsEmpty() {
        assertTrue(adapter.lookup(null).isEmpty());
        assertTrue(adapter.lookup("").isEmpty());
        assertTrue(adapter.lookup("   ").isEmpty());
        verify(listingService, never()).getListingsByIds(any());
    }

    @Test
    @DisplayName("lookupBatch: correctly maps all fields from ListingResponse")
    void lookupBatch_allFieldsMappedCorrectly() {
        ListingResponse listing = createMockListing(
                "list-1",
                "Plumbing Repair Service",
                "Plumbing",
                "Colombo",
                "Colombo District",
                BigDecimal.valueOf(2500),
                PricingModel.HOURLY,
                "https://images.local/plumbing.jpg",
                4.8,
                35,
                ListingStatus.ACTIVE,
                true
        );
        when(listingService.getListingsByIds(List.of("list-1"))).thenReturn(List.of(listing));

        Map<String, FavouriteTargetDetails> result = adapter.lookupBatch(List.of("list-1"));

        assertEquals(1, result.size());
        FavouriteTargetDetails details = result.get("list-1");
        assertNotNull(details);
        assertEquals("list-1", details.targetId());
        assertEquals("Plumbing Repair Service", details.name());
        assertNull(details.subtitle());
        assertEquals("Plumbing", details.category());
        assertEquals("Colombo", details.city());
        assertEquals("https://images.local/plumbing.jpg", details.imageUrl());
        assertEquals(4.8, details.rating());
        assertEquals(35, details.reviewCount());
        assertEquals(BigDecimal.valueOf(2500), details.priceFrom());
        assertEquals("HOURLY", details.priceUnit());
        assertEquals("LKR", details.currency());
        assertFalse(details.inactive());
    }

    @Test
    @DisplayName("lookupBatch: falls back to district when baseCity is null or blank")
    void lookupBatch_fallbackToDistrict() {
        ListingResponse listing = createMockListing(
                "list-2",
                "Gardening Service",
                "Gardening",
                null,
                "Kandy District",
                BigDecimal.valueOf(1500),
                PricingModel.FIXED,
                null,
                0.0,
                0,
                ListingStatus.ACTIVE,
                true
        );
        when(listingService.getListingsByIds(List.of("list-2"))).thenReturn(List.of(listing));

        Map<String, FavouriteTargetDetails> result = adapter.lookupBatch(List.of("list-2"));

        FavouriteTargetDetails details = result.get("list-2");
        assertNotNull(details);
        assertEquals("Kandy District", details.city());
        assertNull(details.imageUrl());
        assertNull(details.rating());
        assertNull(details.reviewCount());
        assertEquals("FIXED", details.priceUnit());
    }

    @Test
    @DisplayName("lookupBatch: maps PAUSED listing as inactive = true")
    void lookupBatch_pausedListing_mappedAsInactive() {
        ListingResponse listing = createMockListing(
                "list-paused",
                "AC Repair",
                "HVAC",
                "Galle",
                "Galle District",
                BigDecimal.valueOf(3000),
                PricingModel.FIXED,
                null,
                4.5,
                10,
                ListingStatus.PAUSED,
                false
        );
        when(listingService.getListingsByIds(List.of("list-paused"))).thenReturn(List.of(listing));

        Map<String, FavouriteTargetDetails> result = adapter.lookupBatch(List.of("list-paused"));

        FavouriteTargetDetails details = result.get("list-paused");
        assertNotNull(details);
        assertTrue(details.inactive(), "PAUSED listing must be marked inactive");
    }

    @Test
    @DisplayName("lookupBatch: maps ACTIVE listing unavailable for booking as inactive = true")
    void lookupBatch_activeUnavailableForBooking_mappedAsInactive() {
        ListingResponse listing = createMockListing(
                "list-unavail",
                "Roofing",
                "Construction",
                "Matara",
                "Matara District",
                BigDecimal.valueOf(5000),
                PricingModel.FIXED,
                null,
                4.0,
                5,
                ListingStatus.ACTIVE,
                false // not available for booking
        );
        when(listingService.getListingsByIds(List.of("list-unavail"))).thenReturn(List.of(listing));

        Map<String, FavouriteTargetDetails> result = adapter.lookupBatch(List.of("list-unavail"));

        FavouriteTargetDetails details = result.get("list-unavail");
        assertNotNull(details);
        assertTrue(details.inactive(), "ACTIVE listing unavailable for booking must be marked inactive");
    }

    @Test
    @DisplayName("lookupBatch: handles mixed found and missing IDs, returning only found")
    void lookupBatch_mixedFoundAndMissing() {
        ListingResponse foundListing = createMockListing(
                "list-found",
                "Painting Service",
                "Painting",
                "Negombo",
                "Gampaha",
                BigDecimal.valueOf(4000),
                PricingModel.FIXED,
                null,
                4.2,
                8,
                ListingStatus.ACTIVE,
                true
        );
        Collection<String> requestedIds = List.of("list-found", "list-missing-1", "list-missing-2");
        when(listingService.getListingsByIds(requestedIds)).thenReturn(List.of(foundListing));

        Map<String, FavouriteTargetDetails> result = adapter.lookupBatch(requestedIds);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("list-found"));
        assertFalse(result.containsKey("list-missing-1"));
        assertFalse(result.containsKey("list-missing-2"));
    }

    @Test
    @DisplayName("lookupBatch: makes batch call exactly once per request (no N+1)")
    void lookupBatch_batchCallMadeExactlyOnce() {
        List<String> ids = List.of("id-1", "id-2", "id-3", "id-4", "id-5");
        when(listingService.getListingsByIds(ids)).thenReturn(List.of());

        adapter.lookupBatch(ids);

        verify(listingService, times(1)).getListingsByIds(ids);
    }

    @Test
    @DisplayName("lookupBatch: propagates exception if ListingService throws")
    void lookupBatch_serviceThrows_propagatesException() {
        List<String> ids = List.of("id-1");
        when(listingService.getListingsByIds(ids)).thenThrow(new RuntimeException("Database connection timeout"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> adapter.lookupBatch(ids));
        assertEquals("Database connection timeout", ex.getMessage());
    }

    @Test
    @DisplayName("lookup: resolves single listing via lookupBatch")
    void lookup_singleListing_found() {
        ListingResponse listing = createMockListing(
                "single-1",
                "Electrical Service",
                "Electrical",
                "Jaffna",
                "Jaffna",
                BigDecimal.valueOf(1800),
                PricingModel.HOURLY,
                "https://images.local/elec.jpg",
                4.9,
                42,
                ListingStatus.ACTIVE,
                true
        );
        when(listingService.getListingsByIds(List.of("single-1"))).thenReturn(List.of(listing));

        Optional<FavouriteTargetDetails> opt = adapter.lookup("single-1");

        assertTrue(opt.isPresent());
        assertEquals("single-1", opt.get().targetId());
        assertEquals("Electrical Service", opt.get().name());
    }

    @Test
    @DisplayName("lookup: returns empty optional when single listing is not found")
    void lookup_singleListing_notFound() {
        when(listingService.getListingsByIds(List.of("single-missing"))).thenReturn(List.of());

        Optional<FavouriteTargetDetails> opt = adapter.lookup("single-missing");

        assertTrue(opt.isEmpty());
    }
}
