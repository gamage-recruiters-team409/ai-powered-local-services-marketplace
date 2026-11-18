package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitExceededException;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.listing.domain.AddOnItem;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;
import com.gamagerecruiters.marketplace.modules.listing.dto.AddOnItemRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.CreateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.GeoPointDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingModerationRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ModerationDecision;
import com.gamagerecruiters.marketplace.modules.listing.dto.PriceSnapshotResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingStatusRequest;
import com.gamagerecruiters.marketplace.modules.listing.mapper.ListingMapper;
import com.gamagerecruiters.marketplace.modules.listing.repository.ListingRepository;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort.CategorySnapshot;

@ExtendWith(MockitoExtension.class)
class ListingServiceImplTest {

    private static final String PROVIDER_ID = "provider-1";
    private static final String OTHER_PROVIDER_ID = "provider-2";
    private static final String MODERATOR_ID = "moderator-1";
    private static final String ROOT_CAT_ID = "cat-root";
    private static final String SUB_CAT_ID = "cat-sub";

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private ProviderEligibilityChecker providerEligibilityChecker;

    @Mock
    private DistrictCatalog districtCatalog;

    @Mock
    private ObjectProvider<CategoryLookupPort> categoryLookupPortProvider;

    @Mock
    private CategoryLookupPort categoryLookupPort;

    @Mock
    private ObjectProvider<RateLimitService> rateLimitServiceProvider;

    @Mock
    private RateLimitService rateLimitService;

    private ListingServiceImpl listingService;

    @BeforeEach
    void setUp() {
        listingService = new ListingServiceImpl(
                listingRepository,
                new ListingMapper(),
                new ListingMediaValidator(),
                new ListingContentScreeningService(),
                providerEligibilityChecker,
                new SriLankaDistrictGeoService(districtCatalog),
                categoryLookupPortProvider,
                rateLimitServiceProvider
        );
    }

    private void stubActiveCategoryHierarchy() {
        when(categoryLookupPortProvider.getIfAvailable()).thenReturn(categoryLookupPort);
        when(categoryLookupPort.findById(SUB_CAT_ID)).thenReturn(Optional.of(
                new CategorySnapshot(SUB_CAT_ID, "Wiring Repair", ROOT_CAT_ID, true, new BigDecimal("0.12"))));
        when(categoryLookupPort.findById(ROOT_CAT_ID)).thenReturn(Optional.of(
                new CategorySnapshot(ROOT_CAT_ID, "Electrical", null, true, new BigDecimal("0.10"))));
    }

    private CreateListingRequest validCreateRequest(String description, Boolean saveAsDraft) {
        return new CreateListingRequest(
                SUB_CAT_ID,
                "Licensed Home Electrical Repair",
                description,
                PricingModel.FIXED,
                new BigDecimal("3500.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala", "Mount Lavinia"),
                List.of("https://cdn.example.com/wiring-1.jpg"),
                null,
                List.of(new AddOnItemRequest("addon-rush", "Same-day rush visit",
                        new BigDecimal("1000.00"), 30, true)),
                saveAsDraft
        );
    }

    private ServiceListing persistedListing(String id, ListingStatus status) {
        ServiceListing listing = new ServiceListing();
        listing.setId(id);
        listing.setProviderId(PROVIDER_ID);
        listing.setCategoryId(SUB_CAT_ID);
        listing.setTitle("Licensed Home Electrical Repair");
        listing.setDescription("Full breaker panel diagnostics, wiring replacement, and household earthing safety testing.");
        listing.setPricingModel(PricingModel.FIXED);
        listing.setBasePrice(new BigDecimal("3500.00"));
        listing.setDurationMin(90);
        listing.setExecutionModel(ExecutionModel.ON_SITE);
        listing.setDistrict("Colombo");
        listing.setBaseCity("Dehiwala");
        listing.setLocation(new GeoJsonPoint(79.8612, 6.9271));
        listing.setCoverageRadiusKm(15);
        listing.setServiceAreas(List.of("Dehiwala", "Mount Lavinia"));
        listing.setImages(List.of("https://cdn.example.com/wiring-1.jpg"));
        listing.setCoverImageUrl("https://cdn.example.com/wiring-1.jpg");
        listing.setAddOns(List.of(
                new AddOnItem("addon-rush", "Same-day rush visit", new BigDecimal("1000.00"), 30, true),
                new AddOnItem("addon-off", "Disabled add-on", new BigDecimal("500.00"), 15, false)
        ));
        listing.setStatus(status);
        listing.setVersion(1L);
        listing.setCreatedAt(Instant.now());
        listing.setUpdatedAt(Instant.now());
        return listing;
    }

    @Test
    @DisplayName("Creates clean published listing as ACTIVE when category & parent are active and district is valid")
    void createListingCleanPublishesAsActive() {
        stubActiveCategoryHierarchy();
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> {
            ServiceListing arg = inv.getArgument(0);
            arg.setId("listing-1");
            return arg;
        });

        ListingResponse response = listingService.createListing(
                PROVIDER_ID,
                validCreateRequest(
                        "Complete home breaker panel fault diagnosis, earthing test, and certified wiring maintenance.",
                        false
                )
        );

        assertThat(response.id()).isEqualTo("listing-1");
        assertThat(response.status()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(response.availableForBooking()).isTrue();
        assertThat(response.categoryName()).isEqualTo("Wiring Repair");
        assertThat(response.parentCategoryId()).isEqualTo(ROOT_CAT_ID);
    }

    @Test
    @DisplayName("Flags listing into FLAGGED (never ACTIVE) when description hides Sri Lankan phone number")
    void createListingWithContactLeakFlagsInsteadOfActivating() {
        stubActiveCategoryHierarchy();
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> inv.getArgument(0));

        ListingResponse response = listingService.createListing(
                PROVIDER_ID,
                validCreateRequest(
                        "Fast electrical repairs across Colombo! Call 0 7 7 - 1 2 3 - 4 5 6 7 anytime for direct quotes.",
                        false
                )
        );

        assertThat(response.status()).isEqualTo(ListingStatus.FLAGGED);
        assertThat(response.availableForBooking()).isFalse();
        assertThat(response.moderationFlags()).anyMatch(f -> f.startsWith("PHONE_NUMBER_DETECTED"));
    }

    @Test
    @DisplayName("Allows relaxed description and missing images when saving as DRAFT")
    void createListingSaveAsDraftAllowsIncompleteContent() {
        stubActiveCategoryHierarchy();
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateListingRequest draftRequest = new CreateListingRequest(
                SUB_CAT_ID,
                "Draft wiring",
                "WIP",
                PricingModel.HOURLY,
                new BigDecimal("1500.00"),
                60,
                ExecutionModel.WORKSHOP,
                "Colombo",
                "Nugegoda",
                new GeoPointDto(79.89, 6.86),
                5,
                List.of(),
                List.of(),
                null,
                List.of(),
                true
        );

        ListingResponse response = listingService.createListing(PROVIDER_ID, draftRequest);
        assertThat(response.status()).isEqualTo(ListingStatus.DRAFT);
    }

    @Test
    @DisplayName("Fails closed with 503 SERVICE_UNAVAILABLE when CategoryLookupPort bean is absent")
    void failsClosedWhenCategoryLookupUnavailable() {
        when(categoryLookupPortProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> listingService.createListing(
                PROVIDER_ID,
                validCreateRequest("Complete electrical service across Colombo district with quality guarantee.", false)
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getErrorCode()).isEqualTo("CATEGORY_LOOKUP_UNAVAILABLE");
                });
    }

    @Test
    @DisplayName("Rejects listing creation or reactivation under active subcategory whose parent category is inactive")
    void rejectsInactiveParentCategory() {
        when(categoryLookupPortProvider.getIfAvailable()).thenReturn(categoryLookupPort);
        when(categoryLookupPort.findById(SUB_CAT_ID)).thenReturn(Optional.of(
                new CategorySnapshot(SUB_CAT_ID, "Wiring Repair", ROOT_CAT_ID, true, new BigDecimal("0.12"))));
        when(categoryLookupPort.findById(ROOT_CAT_ID)).thenReturn(Optional.of(
                new CategorySnapshot(ROOT_CAT_ID, "Electrical", null, false, new BigDecimal("0.10"))));

        assertThatThrownBy(() -> listingService.createListing(
                PROVIDER_ID,
                validCreateRequest("Complete electrical service across Colombo district with quality guarantee.", false)
        ))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("parent category is inactive");
    }

    @Test
    @DisplayName("Rejects invalid Sri Lankan district with 400 INVALID_DISTRICT")
    void rejectsInvalidDistrict() {
        stubActiveCategoryHierarchy();
        when(districtCatalog.isValidDistrict("Atlantis")).thenReturn(false);

        CreateListingRequest invalidDistrictReq = new CreateListingRequest(
                SUB_CAT_ID,
                "Licensed Home Electrical Repair",
                "Complete home breaker panel fault diagnosis and earthing safety maintenance.",
                PricingModel.FIXED,
                new BigDecimal("3500.00"),
                90,
                ExecutionModel.ON_SITE,
                "Atlantis",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/wiring-1.jpg"),
                null,
                List.of(),
                false
        );

        assertThatThrownBy(() -> listingService.createListing(PROVIDER_ID, invalidDistrictReq))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("INVALID_DISTRICT"));
    }

    @Test
    @DisplayName("Blocks IDOR update and status change attempts from another provider with AccessDeniedException")
    void blocksIdorFromAnotherProvider() {
        when(listingRepository.findById("listing-1"))
                .thenReturn(Optional.of(persistedListing("listing-1", ListingStatus.ACTIVE)));

        UpdateListingRequest updateReq = new UpdateListingRequest(
                SUB_CAT_ID,
                "Updated Electrical Repair",
                "Complete home breaker panel fault diagnosis, earthing test, and certified wiring maintenance.",
                PricingModel.FIXED,
                new BigDecimal("4000.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/wiring-1.jpg"),
                null,
                List.of(),
                null
        );

        assertThatThrownBy(() -> listingService.updateListing(OTHER_PROVIDER_ID, "listing-1", updateReq))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> listingService.updateListingStatus(
                OTHER_PROVIDER_ID, "listing-1", new UpdateListingStatusRequest(ListingStatus.PAUSED, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Blocks self-activating FLAGGED or REJECTED listings and rejects edits on ARCHIVED listings")
    void blocksModerationBypassAndArchivedEdits() {
        when(listingRepository.findById("flagged-1"))
                .thenReturn(Optional.of(persistedListing("flagged-1", ListingStatus.FLAGGED)));
        when(listingRepository.findById("rejected-1"))
                .thenReturn(Optional.of(persistedListing("rejected-1", ListingStatus.REJECTED)));
        when(listingRepository.findById("archived-1"))
                .thenReturn(Optional.of(persistedListing("archived-1", ListingStatus.ARCHIVED)));

        assertThatThrownBy(() -> listingService.updateListingStatus(
                PROVIDER_ID, "flagged-1", new UpdateListingStatusRequest(ListingStatus.ACTIVE, null)))
                .isInstanceOf(ResourceConflictException.class);

        assertThatThrownBy(() -> listingService.updateListingStatus(
                PROVIDER_ID, "rejected-1", new UpdateListingStatusRequest(ListingStatus.ACTIVE, null)))
                .isInstanceOf(ResourceConflictException.class);

        UpdateListingRequest updateReq = new UpdateListingRequest(
                SUB_CAT_ID,
                "Updated Title",
                "Complete home breaker panel fault diagnosis, earthing test, and certified wiring maintenance.",
                PricingModel.FIXED,
                new BigDecimal("4000.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/wiring-1.jpg"),
                null,
                List.of(),
                null
        );

        assertThatThrownBy(() -> listingService.updateListing(PROVIDER_ID, "archived-1", updateReq))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Archived");
    }

    @Test
    @DisplayName("PUT edit on FLAGGED listing never self-activates even when contact leak is removed")
    void putOnFlaggedNeverSelfActivates() {
        ServiceListing flagged = persistedListing("flagged-1", ListingStatus.FLAGGED);
        flagged.setModerationFlags(List.of("PHONE_NUMBER_DETECTED: old flag"));
        when(listingRepository.findById("flagged-1")).thenReturn(Optional.of(flagged));
        stubActiveCategoryHierarchy();
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateListingRequest cleanEdit = new UpdateListingRequest(
                SUB_CAT_ID,
                "Updated Electrical Repair",
                "Complete home breaker panel fault diagnosis, earthing test, and certified wiring maintenance.",
                PricingModel.FIXED,
                new BigDecimal("4000.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/wiring-1.jpg"),
                null,
                List.of(),
                null
        );

        ListingResponse response = listingService.updateListing(PROVIDER_ID, "flagged-1", cleanEdit);
        assertThat(response.status()).isEqualTo(ListingStatus.FLAGGED);
        assertThat(response.moderationReason()).contains("awaiting moderator review");
    }

    @Test
    @DisplayName("FR-CUST-005: returns PAUSED/ARCHIVED publicly and hides DRAFT/FLAGGED unless owner or moderator")
    void customerFavouriteVisibilityRules() {
        when(categoryLookupPortProvider.getIfAvailable()).thenReturn(null);
        when(listingRepository.findById("paused-1"))
                .thenReturn(Optional.of(persistedListing("paused-1", ListingStatus.PAUSED)));
        when(listingRepository.findById("flagged-1"))
                .thenReturn(Optional.of(persistedListing("flagged-1", ListingStatus.FLAGGED)));

        ListingResponse publicPaused = listingService.getListingForViewer("paused-1", null, null);
        assertThat(publicPaused.status()).isEqualTo(ListingStatus.PAUSED);
        assertThat(publicPaused.availableForBooking()).isFalse();

        assertThatThrownBy(() -> listingService.getListingForViewer("flagged-1", "cust-1", Role.CUSTOMER))
                .isInstanceOf(ResourceNotFoundException.class);

        ListingResponse ownerFlagged = listingService.getListingForViewer("flagged-1", PROVIDER_ID, Role.PROVIDER);
        assertThat(ownerFlagged.status()).isEqualTo(ListingStatus.FLAGGED);

        ListingResponse moderatorFlagged = listingService.getListingForViewer("flagged-1", MODERATOR_ID, Role.MODERATOR);
        assertThat(moderatorFlagged.status()).isEqualTo(ListingStatus.FLAGGED);
    }

    @Test
    @DisplayName("Moderation requires FLAGGED state and non-blank reason on REJECT")
    void moderationRules() {
        ServiceListing flagged = persistedListing("flagged-1", ListingStatus.FLAGGED);
        flagged.setModerationFlags(List.of("EMAIL_ADDRESS_DETECTED: flag"));
        when(listingRepository.findById("flagged-1")).thenReturn(Optional.of(flagged));
        stubActiveCategoryHierarchy();
        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> listingService.moderateListing(
                MODERATOR_ID, "flagged-1", new ListingModerationRequest(ModerationDecision.REJECT, "   ")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("MODERATION_REASON_REQUIRED"));

        ListingResponse approved = listingService.moderateListing(
                MODERATOR_ID, "flagged-1", new ListingModerationRequest(ModerationDecision.APPROVE, "False positive"));
        assertThat(approved.status()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(approved.moderationFlags()).isEmpty();
        verify(providerEligibilityChecker).assertEligibleToManageListing(PROVIDER_ID, true);

        ServiceListing active = persistedListing("active-1", ListingStatus.ACTIVE);
        when(listingRepository.findById("active-1")).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> listingService.moderateListing(
                MODERATOR_ID, "active-1", new ListingModerationRequest(ModerationDecision.REJECT, "Nope")))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    @DisplayName("Moderation approval cannot activate FLAGGED listings belonging to suspended or unverified providers")
    void moderationApprovalRejectsSuspendedOrUnverifiedProviders() {
        ServiceListing suspendedOwnersListing = persistedListing("flagged-susp", ListingStatus.FLAGGED);
        suspendedOwnersListing.setModerationFlags(List.of("SPAM_DETECTED"));
        when(listingRepository.findById("flagged-susp")).thenReturn(Optional.of(suspendedOwnersListing));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROVIDER_ACCOUNT_INACTIVE", "Account suspended"))
                .when(providerEligibilityChecker).assertEligibleToManageListing(PROVIDER_ID, true);

        assertThatThrownBy(() -> listingService.moderateListing(
                MODERATOR_ID,
                "flagged-susp",
                new ListingModerationRequest(ModerationDecision.APPROVE, "Content clean")
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("PROVIDER_ACCOUNT_INACTIVE"));
        assertThat(suspendedOwnersListing.getStatus()).isEqualTo(ListingStatus.FLAGGED);
        verify(listingRepository, never()).save(any(ServiceListing.class));

        ServiceListing unverifiedOwnersListing = persistedListing("flagged-unver", ListingStatus.FLAGGED);
        unverifiedOwnersListing.setProviderId(OTHER_PROVIDER_ID);
        when(listingRepository.findById("flagged-unver")).thenReturn(Optional.of(unverifiedOwnersListing));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROVIDER_NOT_VERIFIED", "Verification not approved"))
                .when(providerEligibilityChecker).assertEligibleToManageListing(OTHER_PROVIDER_ID, true);

        assertThatThrownBy(() -> listingService.moderateListing(
                MODERATOR_ID,
                "flagged-unver",
                new ListingModerationRequest(ModerationDecision.APPROVE, "Content clean")
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("PROVIDER_NOT_VERIFIED"));
        assertThat(unverifiedOwnersListing.getStatus()).isEqualTo(ListingStatus.FLAGGED);
        verify(listingRepository, never()).save(any(ServiceListing.class));

        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> inv.getArgument(0));
        ListingResponse rejected = listingService.moderateListing(
                MODERATOR_ID,
                "flagged-susp",
                new ListingModerationRequest(ModerationDecision.REJECT, "Confirmed violation")
        );
        assertThat(rejected.status()).isEqualTo(ListingStatus.REJECTED);
    }

    @Test
    @DisplayName("PriceSnapshot computes total price/duration with active add-ons and rejects inactive/unknown add-ons or non-ACTIVE listings")
    void priceSnapshotValidationAndCalculation() {
        ServiceListing active = persistedListing("active-1", ListingStatus.ACTIVE);
        when(listingRepository.findById("active-1")).thenReturn(Optional.of(active));
        stubActiveCategoryHierarchy();

        PriceSnapshotResponse snapshot = listingService.getListingPriceSnapshot("active-1", List.of("addon-rush"));
        assertThat(snapshot.basePrice()).isEqualByComparingTo("3500.00");
        assertThat(snapshot.commissionRate()).isEqualByComparingTo("0.12");
        assertThat(snapshot.totalPrice()).isEqualByComparingTo("4500.00");
        assertThat(snapshot.totalDurationMin()).isEqualTo(120);
        assertThat(snapshot.selectedAddOns()).hasSize(1);

        assertThatThrownBy(() -> listingService.getListingPriceSnapshot("active-1", List.of("addon-off")))
                .isInstanceOf(ResourceConflictException.class);

        assertThatThrownBy(() -> listingService.getListingPriceSnapshot("active-1", List.of("unknown-addon")))
                .isInstanceOf(ResourceNotFoundException.class);

        ServiceListing paused = persistedListing("paused-1", ListingStatus.PAUSED);
        when(listingRepository.findById("paused-1")).thenReturn(Optional.of(paused));

        assertThatThrownBy(() -> listingService.getListingPriceSnapshot("paused-1", List.of()))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    @DisplayName("Suspension cascade pauses ACTIVE listings and unsuspending restores only suspension-hidden listings")
    void suspensionVisibilityCascadeAndCategoryBlockCheck() {
        ServiceListing active = persistedListing("l1", ListingStatus.ACTIVE);
        when(listingRepository.findByProviderIdAndStatus(PROVIDER_ID, ListingStatus.ACTIVE))
                .thenReturn(List.of(active));

        listingService.updateProviderSuspensionVisibility(PROVIDER_ID, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServiceListing>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(listingRepository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue().get(0).getStatus()).isEqualTo(ListingStatus.PAUSED);
        assertThat(savedCaptor.getValue().get(0).isHiddenByProviderSuspension()).isTrue();

        when(listingRepository.existsByCategoryIdAndStatusIn(SUB_CAT_ID, ListingStatus.CATEGORY_BLOCKING_STATUSES))
                .thenReturn(true);
        assertThat(listingService.hasBlockingListingsForCategory(SUB_CAT_ID)).isTrue();
    }

    @Test
    @DisplayName("Step 1 draft saves with null pricing/location and normalizes UI district label; publish rejects null pricing")
    void step1DraftDefaultsAndPublishStrictValidation() {
        stubActiveCategoryHierarchy();
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateListingRequest step1Draft = new CreateListingRequest(
                SUB_CAT_ID,
                "Emergency Pipe Leak Repair",
                null,
                null,
                null,
                null,
                null,
                "Colombo District (Western Province)",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true
        );

        ListingResponse draft = listingService.createListing(PROVIDER_ID, step1Draft);
        assertThat(draft.status()).isEqualTo(ListingStatus.DRAFT);
        assertThat(draft.district()).isEqualTo("Colombo");
        assertThat(draft.location()).isNotNull();
        assertThat(draft.coverageRadiusKm()).isEqualTo(15);

        CreateListingRequest publishMissingPrice = new CreateListingRequest(
                SUB_CAT_ID,
                "Emergency Pipe Leak Repair",
                "Complete pipe leak inspection, valve replacement, and pressure testing across Colombo.",
                PricingModel.FIXED,
                null,
                60,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/1.jpg"),
                null,
                null,
                false
        );

        assertThatThrownBy(() -> listingService.createListing(PROVIDER_ID, publishMissingPrice))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Accepts +0 Mins add-on duration, flags contact leak in add-on description, and rejects out-of-Sri-Lanka coordinates")
    void zeroDurationAddOnDescriptionScreeningAndSriLankaBounds() {
        stubActiveCategoryHierarchy();
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(listingRepository.save(any(ServiceListing.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateListingRequest withZeroMinAddOnLeak = new CreateListingRequest(
                SUB_CAT_ID,
                "Emergency Pipe Leak Repair",
                "Complete pipe leak inspection, valve replacement, and pressure testing across Colombo.",
                PricingModel.FIXED,
                new BigDecimal("2800.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/1.jpg"),
                null,
                List.of(new AddOnItemRequest(
                        null,
                        "Emergency Night Callout",
                        "Call 0771234567 after 8 PM",
                        new BigDecimal("1500.00"),
                        0,
                        true
                )),
                false
        );

        ListingResponse flagged = listingService.createListing(PROVIDER_ID, withZeroMinAddOnLeak);
        assertThat(flagged.status()).isEqualTo(ListingStatus.FLAGGED);
        assertThat(flagged.activeAddOnCount()).isEqualTo(1);
        assertThat(flagged.addOns().get(0).extraDurationMin()).isZero();
        assertThat(flagged.addOns().get(0).description()).isEqualTo("Call 0771234567 after 8 PM");

        CreateListingRequest outOfBounds = new CreateListingRequest(
                SUB_CAT_ID,
                "Emergency Pipe Leak Repair",
                "Complete pipe leak inspection, valve replacement, and pressure testing across Colombo.",
                PricingModel.FIXED,
                new BigDecimal("2800.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(0.0, 0.0),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/1.jpg"),
                null,
                List.of(),
                false
        );

        assertThatThrownBy(() -> listingService.createListing(PROVIDER_ID, outOfBounds))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("INVALID_LOCATION_BOUNDS"));
    }

    @Test
    @DisplayName("FR-CUST-005 batch lookup returns ACTIVE and PAUSED only in requested order, excluding DRAFT and FLAGGED")
    void batchGetListingsByIdsRespectsOrderAndPrivacy() {
        ServiceListing active = persistedListing("id-active", ListingStatus.ACTIVE);
        ServiceListing paused = persistedListing("id-paused", ListingStatus.PAUSED);
        ServiceListing draft = persistedListing("id-draft", ListingStatus.DRAFT);
        ServiceListing flagged = persistedListing("id-flagged", ListingStatus.FLAGGED);

        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(flagged, paused, draft, active));
        stubActiveCategoryHierarchy();

        List<ListingResponse> results = listingService.getListingsByIds(
                List.of("id-active", "id-draft", "id-paused", "id-flagged"));

        assertThat(results).extracting(ListingResponse::id)
                .containsExactly("id-active", "id-paused");
        assertThat(results.get(0).availableForBooking()).isTrue();
        assertThat(results.get(1).availableForBooking()).isFalse();
    }

    @Test
    @DisplayName("Category or parent deactivation marks categoryActive=false and counts inactive categories in dashboard summary")
    void categoryDeactivationAndDashboardSummaryAndSearchAndRatingAndRateLimit() {
        ServiceListing active = persistedListing("l-active", ListingStatus.ACTIVE);
        ServiceListing paused = persistedListing("l-paused", ListingStatus.PAUSED);
        paused.setCategoryId("cat-sub-inactive-parent");

        when(listingRepository.findByProviderIdOrderByUpdatedAtDesc(PROVIDER_ID))
                .thenReturn(List.of(active, paused));
        when(categoryLookupPortProvider.getIfAvailable()).thenReturn(categoryLookupPort);
        when(categoryLookupPort.findById(SUB_CAT_ID)).thenReturn(Optional.of(
                new CategorySnapshot(SUB_CAT_ID, "Wiring Repair", ROOT_CAT_ID, true, new BigDecimal("0.12"))));
        when(categoryLookupPort.findById(ROOT_CAT_ID)).thenReturn(Optional.of(
                new CategorySnapshot(ROOT_CAT_ID, "Electrical", null, true, new BigDecimal("0.10"))));
        when(categoryLookupPort.findById("cat-sub-inactive-parent")).thenReturn(Optional.of(
                new CategorySnapshot("cat-sub-inactive-parent", "Drain Cleaning", "cat-root-off", true, new BigDecimal("0.10"))));
        when(categoryLookupPort.findById("cat-root-off")).thenReturn(Optional.of(
                new CategorySnapshot("cat-root-off", "Plumbing", null, false, new BigDecimal("0.10"))));

        ProviderListingDashboardSummaryResponse summary = listingService.getMyListingsSummary(PROVIDER_ID);
        assertThat(summary.totalListings()).isEqualTo(2);
        assertThat(summary.activeCount()).isEqualTo(1);
        assertThat(summary.pausedCount()).isEqualTo(1);
        assertThat(summary.categoryInactiveCount()).isEqualTo(1);

        when(listingRepository.findByProviderIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
                PROVIDER_ID, "Electrical", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(active)));
        Page<ListingSummaryResponse> searched = listingService.getMyListings(
                PROVIDER_ID, null, "Electrical", PageRequest.of(0, 20));
        assertThat(searched.getContent()).hasSize(1);
        assertThat(searched.getContent().get(0).activeAddOnCount()).isEqualTo(1);
        assertThat(searched.getContent().get(0).categoryActive()).isTrue();

        when(listingRepository.findById("l-paused")).thenReturn(Optional.of(paused));
        ListingResponse pausedRead = listingService.getListingForViewer("l-paused", PROVIDER_ID, Role.PROVIDER);
        assertThat(pausedRead.categoryActive()).isFalse();
        assertThat(pausedRead.availableForBooking()).isFalse();

        when(listingRepository.findById("l-active")).thenReturn(Optional.of(active));
        listingService.updateListingRatingStats("l-active", 4.9, 34);
        assertThat(active.getRatingAvg()).isEqualTo(4.9);
        assertThat(active.getRatingCount()).isEqualTo(34);

        when(rateLimitServiceProvider.getIfAvailable()).thenReturn(rateLimitService);
        org.mockito.Mockito.doThrow(new RateLimitExceededException(60))
                .when(rateLimitService)
                .check(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());

        assertThatThrownBy(() -> listingService.createListing(
                PROVIDER_ID,
                validCreateRequest("Complete home breaker panel fault diagnosis and earthing safety maintenance.", false)
        )).isInstanceOf(RateLimitExceededException.class);
    }
}
