package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerFavourite;
import com.gamagerecruiters.marketplace.modules.customer.domain.FavouriteType;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouritePageResponse;
import com.gamagerecruiters.marketplace.modules.customer.dto.CustomerFavouriteResponse;
import com.gamagerecruiters.marketplace.modules.customer.repository.CustomerFavouriteRepository;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

@ExtendWith(MockitoExtension.class)
class CustomerFavouriteServiceTest {

    @Mock
    private CustomerFavouriteRepository customerFavouriteRepository;

    @Mock
    private FavouriteProviderLookup favouriteProviderLookup;

    @Mock
    private ObjectProvider<FavouriteListingLookup> listingLookupProvider;

    @Mock
    private FavouriteListingLookup favouriteListingLookup;

    private CustomerFavouriteServiceImpl customerFavouriteService;

    @BeforeEach
    void setUp() {
        customerFavouriteService = new CustomerFavouriteServiceImpl(
                customerFavouriteRepository,
                favouriteProviderLookup,
                listingLookupProvider
        );
    }

    @Test
    @DisplayName("addFavourite: saves new favourite when target exists and not previously saved")
    void addFavourite_success() {
        String customerId = "cust-1";
        String targetId = "prov-1";

        FavouriteTargetDetails details = new FavouriteTargetDetails(
                targetId, "Nuwan Perera", null, null, "Colombo", "https://img.local/nuwan.jpg",
                null, null, null, null, null, false
        );
        when(favouriteProviderLookup.lookup(targetId)).thenReturn(Optional.of(details));
        when(customerFavouriteRepository.findByCustomerIdAndTypeAndTargetId(customerId, FavouriteType.PROVIDER, targetId))
                .thenReturn(Optional.empty());
        when(customerFavouriteRepository.save(any(CustomerFavourite.class)))
                .thenAnswer(inv -> {
                    CustomerFavourite f = inv.getArgument(0);
                    f.setId("fav-generated-id");
                    return f;
                });

        CustomerFavouriteResponse response = customerFavouriteService.addFavourite(customerId, FavouriteType.PROVIDER, targetId);

        ArgumentCaptor<CustomerFavourite> captor = ArgumentCaptor.forClass(CustomerFavourite.class);
        verify(customerFavouriteRepository).save(captor.capture());
        CustomerFavourite saved = captor.getValue();
        assertEquals(customerId, saved.getCustomerId());
        assertEquals(FavouriteType.PROVIDER, saved.getType());
        assertEquals(targetId, saved.getTargetId());
        assertNotNull(saved.getCreatedAt());

        assertNotNull(response);
        assertEquals("prov-1", response.targetId());
        assertEquals("Nuwan Perera", response.name());
        assertEquals("Colombo", response.city());
        assertEquals("https://img.local/nuwan.jpg", response.imageUrl());
        assertFalse(response.inactive());
    }

    @Test
    @DisplayName("addFavourite: is idempotent when target is already saved (no-op)")
    void addFavourite_alreadySaved_isIdempotent() {
        String customerId = "cust-1";
        String targetId = "prov-1";

        FavouriteTargetDetails details = new FavouriteTargetDetails(
                targetId, "Nuwan Perera", null, null, "Colombo", null,
                null, null, null, null, null, false
        );
        CustomerFavourite existing = new CustomerFavourite(customerId, FavouriteType.PROVIDER, targetId, Instant.now());
        existing.setId("fav-existing");

        when(favouriteProviderLookup.lookup(targetId)).thenReturn(Optional.of(details));
        when(customerFavouriteRepository.findByCustomerIdAndTypeAndTargetId(customerId, FavouriteType.PROVIDER, targetId))
                .thenReturn(Optional.of(existing));

        CustomerFavouriteResponse response = customerFavouriteService.addFavourite(customerId, FavouriteType.PROVIDER, targetId);

        verify(customerFavouriteRepository, never()).save(any());
        assertNotNull(response);
        assertEquals("prov-1", response.targetId());
        assertEquals("Nuwan Perera", response.name());
    }

    @Test
    @DisplayName("addFavourite: handles duplicate-key race gracefully as idempotent success")
    void addFavourite_duplicateKeyRace_treatedAsSuccess() {
        String customerId = "cust-1";
        String targetId = "prov-1";

        FavouriteTargetDetails details = new FavouriteTargetDetails(
                targetId, "Nuwan Perera", null, null, "Colombo", null,
                null, null, null, null, null, false
        );
        CustomerFavourite existing = new CustomerFavourite(customerId, FavouriteType.PROVIDER, targetId, Instant.now());
        existing.setId("fav-race-won");

        when(favouriteProviderLookup.lookup(targetId)).thenReturn(Optional.of(details));
        when(customerFavouriteRepository.findByCustomerIdAndTypeAndTargetId(customerId, FavouriteType.PROVIDER, targetId))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(customerFavouriteRepository.save(any(CustomerFavourite.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error"));

        CustomerFavouriteResponse response = customerFavouriteService.addFavourite(customerId, FavouriteType.PROVIDER, targetId);

        assertNotNull(response);
        assertEquals("prov-1", response.targetId());
        assertEquals("Nuwan Perera", response.name());
    }

    @Test
    @DisplayName("addFavourite: throws 404 TARGET_NOT_FOUND when target does not exist")
    void addFavourite_targetNotFound_throws404() {
        String customerId = "cust-1";
        String targetId = "prov-missing";

        when(favouriteProviderLookup.lookup(targetId)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(
                ResourceNotFoundException.class,
                () -> customerFavouriteService.addFavourite(customerId, FavouriteType.PROVIDER, targetId)
        );
        assertEquals("TARGET_NOT_FOUND", ex.getErrorCode());
        verify(customerFavouriteRepository, never()).save(any());
    }

    @Test
    @DisplayName("addFavourite: throws 503 FAVOURITES_LISTING_UNAVAILABLE when listing lookup is absent")
    void addFavourite_listingUnavailable_throws503() {
        when(listingLookupProvider.getIfAvailable()).thenReturn(null);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerFavouriteService.addFavourite("cust-1", FavouriteType.LISTING, "list-1")
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("FAVOURITES_LISTING_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("removeFavourite: deletes favourite idempotently")
    void removeFavourite_success() {
        String customerId = "cust-1";
        String targetId = "prov-1";

        customerFavouriteService.removeFavourite(customerId, FavouriteType.PROVIDER, targetId);

        verify(customerFavouriteRepository).deleteByCustomerIdAndTypeAndTargetId(customerId, FavouriteType.PROVIDER, targetId);
    }

    @Test
    @DisplayName("getFavourites: throws 503 FAVOURITES_LISTING_UNAVAILABLE when listing lookup is absent")
    void getFavourites_listingUnavailable_throws503() {
        when(listingLookupProvider.getIfAvailable()).thenReturn(null);

        ApiException ex = assertThrows(
                ApiException.class,
                () -> customerFavouriteService.getFavourites("cust-1", FavouriteType.LISTING, 0, 6)
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("FAVOURITES_LISTING_UNAVAILABLE", ex.getErrorCode());
    }

    @Test
    @DisplayName("getFavourites: returns paginated favourites with mapped inactive status")
    void getFavourites_mapsInactiveCorrectly() {
        String customerId = "cust-1";
        Instant now = Instant.now();

        CustomerFavourite favActive = new CustomerFavourite(customerId, FavouriteType.PROVIDER, "prov-active", now.minusSeconds(10));
        favActive.setId("f-1");
        CustomerFavourite favSuspended = new CustomerFavourite(customerId, FavouriteType.PROVIDER, "prov-suspended", now.minusSeconds(20));
        favSuspended.setId("f-2");

        Page<CustomerFavourite> page = new PageImpl<>(List.of(favActive, favSuspended), PageRequest.of(0, 6), 2);
        when(customerFavouriteRepository.findByCustomerIdAndTypeOrderByCreatedAtDesc(eq(customerId), eq(FavouriteType.PROVIDER), any(Pageable.class)))
                .thenReturn(page);

        FavouriteTargetDetails detailsActive = new FavouriteTargetDetails(
                "prov-active", "Active Provider", null, null, "Colombo", "https://img/1.jpg",
                null, null, null, null, null, false
        );
        FavouriteTargetDetails detailsSuspended = new FavouriteTargetDetails(
                "prov-suspended", "Suspended Provider", null, null, "Gampaha", "https://img/2.jpg",
                null, null, null, null, null, true
        );
        when(favouriteProviderLookup.lookupBatch(List.of("prov-active", "prov-suspended")))
                .thenReturn(Map.of("prov-active", detailsActive, "prov-suspended", detailsSuspended));

        CustomerFavouritePageResponse response = customerFavouriteService.getFavourites(customerId, FavouriteType.PROVIDER, 0, 6);

        assertEquals(2, response.content().size());
        assertEquals(2, response.totalElements());

        CustomerFavouriteResponse item1 = response.content().get(0);
        assertEquals("prov-active", item1.targetId());
        assertEquals("Active Provider", item1.name());
        assertFalse(item1.inactive());

        CustomerFavouriteResponse item2 = response.content().get(1);
        assertEquals("prov-suspended", item2.targetId());
        assertEquals("Suspended Provider", item2.name());
        assertTrue(item2.inactive());
    }

    @Test
    @DisplayName("getFavourites: silently skips targets that no longer exist without failing the page")
    void getFavourites_skipsMissingTargets() {
        String customerId = "cust-1";
        Instant now = Instant.now();

        CustomerFavourite favExisting = new CustomerFavourite(customerId, FavouriteType.PROVIDER, "prov-existing", now.minusSeconds(10));
        favExisting.setId("f-1");
        CustomerFavourite favDeleted = new CustomerFavourite(customerId, FavouriteType.PROVIDER, "prov-deleted", now.minusSeconds(20));
        favDeleted.setId("f-2");

        Page<CustomerFavourite> page = new PageImpl<>(List.of(favExisting, favDeleted), PageRequest.of(0, 6), 2);
        when(customerFavouriteRepository.findByCustomerIdAndTypeOrderByCreatedAtDesc(eq(customerId), eq(FavouriteType.PROVIDER), any(Pageable.class)))
                .thenReturn(page);

        FavouriteTargetDetails detailsExisting = new FavouriteTargetDetails(
                "prov-existing", "Existing Provider", null, null, "Colombo", null,
                null, null, null, null, null, false
        );
        // "prov-deleted" not in map -> missing
        when(favouriteProviderLookup.lookupBatch(List.of("prov-existing", "prov-deleted")))
                .thenReturn(Map.of("prov-existing", detailsExisting));

        CustomerFavouritePageResponse response = customerFavouriteService.getFavourites(customerId, FavouriteType.PROVIDER, 0, 6);

        assertEquals(1, response.content().size());
        assertEquals("prov-existing", response.content().get(0).targetId());
        assertEquals(2, response.totalElements());
    }

    @Test
    @DisplayName("getFavourites: listing favourites list correctly via FavouriteListingLookupAdapter")
    void getFavourites_listingFavouritesListCorrectlyViaAdapter() {
        String customerId = "cust-1";
        Instant now = Instant.now();

        CustomerFavourite favListing = new CustomerFavourite(customerId, FavouriteType.LISTING, "list-10", now);
        favListing.setId("f-10");

        Page<CustomerFavourite> page = new PageImpl<>(List.of(favListing), PageRequest.of(0, 6), 1);
        when(customerFavouriteRepository.findByCustomerIdAndTypeOrderByCreatedAtDesc(eq(customerId), eq(FavouriteType.LISTING), any(Pageable.class)))
                .thenReturn(page);

        ListingService mockListingService = org.mockito.Mockito.mock(ListingService.class);
        FavouriteListingLookupAdapter listingAdapter = new FavouriteListingLookupAdapter(mockListingService);
        when(listingLookupProvider.getIfAvailable()).thenReturn(listingAdapter);

        ListingResponse mockListing = new ListingResponse(
                "list-10",
                "prov-1",
                "cat-1",
                "Cleaning",
                null,
                "Home Cleaning",
                "Full home cleaning service",
                PricingModel.FIXED,
                BigDecimal.valueOf(3500),
                120,
                ExecutionModel.ON_SITE,
                "Colombo District",
                "Colombo",
                null,
                15,
                List.of(),
                List.of(),
                "https://images.local/cleaning.jpg",
                List.of(),
                ListingStatus.ACTIVE,
                true,
                true,
                0,
                List.of(),
                null,
                null,
                4.7,
                18,
                1L,
                now,
                now
        );
        when(mockListingService.getListingsByIds(List.of("list-10"))).thenReturn(List.of(mockListing));

        CustomerFavouritePageResponse response = customerFavouriteService.getFavourites(customerId, FavouriteType.LISTING, 0, 6);

        assertEquals(1, response.content().size());
        CustomerFavouriteResponse item = response.content().get(0);
        assertEquals("list-10", item.targetId());
        assertEquals("Home Cleaning", item.name());
        assertEquals("Cleaning", item.category());
        assertEquals("Colombo", item.city());
        assertEquals("https://images.local/cleaning.jpg", item.imageUrl());
        assertEquals(4.7, item.rating());
        assertEquals(18, item.reviewCount());
        assertEquals(BigDecimal.valueOf(3500), item.priceFrom());
        assertEquals("FIXED", item.priceUnit());
        assertEquals("LKR", item.currency());
        assertFalse(item.inactive());
    }
}
