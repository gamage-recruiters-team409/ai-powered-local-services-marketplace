package com.gamagerecruiters.marketplace.modules.listing.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.listing.domain.AddOnItem;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;
import com.gamagerecruiters.marketplace.modules.listing.dto.AddOnItemRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.AddOnItemResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.CreateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.GeoPointDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingModerationRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ModerationDecision;
import com.gamagerecruiters.marketplace.modules.listing.dto.PriceSnapshotResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProvinceGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingRequest;
import com.gamagerecruiters.marketplace.modules.listing.dto.UpdateListingStatusRequest;
import com.gamagerecruiters.marketplace.modules.listing.mapper.ListingMapper;
import com.gamagerecruiters.marketplace.modules.listing.repository.ListingRepository;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort.CategorySnapshot;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingContentScreeningService.ScreeningResult;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingMediaValidator.ValidatedMedia;

@Service
public class ListingServiceImpl implements ListingService {

    private static final int MIN_PUBLISH_TITLE_LENGTH = 10;
    private static final int MIN_PUBLISH_DESCRIPTION_LENGTH = 50;
    private static final int DEFAULT_DRAFT_COVERAGE_RADIUS_KM = 15;
    private static final String BUCKET_LISTING_CREATE = "listing-create";
    private static final int CREATE_RATE_LIMIT_MAX_REQUESTS = 20;
    private static final Duration CREATE_RATE_LIMIT_WINDOW = Duration.ofMinutes(15);

    private final ListingRepository listingRepository;
    private final ListingMapper listingMapper;
    private final ListingMediaValidator mediaValidator;
    private final ListingContentScreeningService contentScreeningService;
    private final ProviderEligibilityChecker providerEligibilityChecker;
    private final SriLankaDistrictGeoService districtGeoService;
    private final ObjectProvider<CategoryLookupPort> categoryLookupPortProvider;
    private final ObjectProvider<RateLimitService> rateLimitServiceProvider;

    public ListingServiceImpl(
            ListingRepository listingRepository,
            ListingMapper listingMapper,
            ListingMediaValidator mediaValidator,
            ListingContentScreeningService contentScreeningService,
            ProviderEligibilityChecker providerEligibilityChecker,
            DistrictCatalog districtCatalog,
            ObjectProvider<CategoryLookupPort> categoryLookupPortProvider
    ) {
        this(
                listingRepository,
                listingMapper,
                mediaValidator,
                contentScreeningService,
                providerEligibilityChecker,
                new SriLankaDistrictGeoService(districtCatalog),
                categoryLookupPortProvider,
                null
        );
    }

    @Autowired
    public ListingServiceImpl(
            ListingRepository listingRepository,
            ListingMapper listingMapper,
            ListingMediaValidator mediaValidator,
            ListingContentScreeningService contentScreeningService,
            ProviderEligibilityChecker providerEligibilityChecker,
            SriLankaDistrictGeoService districtGeoService,
            ObjectProvider<CategoryLookupPort> categoryLookupPortProvider,
            ObjectProvider<RateLimitService> rateLimitServiceProvider
    ) {
        this.listingRepository = listingRepository;
        this.listingMapper = listingMapper;
        this.mediaValidator = mediaValidator;
        this.contentScreeningService = contentScreeningService;
        this.providerEligibilityChecker = providerEligibilityChecker;
        this.districtGeoService = districtGeoService;
        this.categoryLookupPortProvider = categoryLookupPortProvider;
        this.rateLimitServiceProvider = rateLimitServiceProvider;
    }

    @Override
    public ListingResponse createListing(String providerUserId, CreateListingRequest request) {
        boolean saveAsDraft = Boolean.TRUE.equals(request.saveAsDraft());
        boolean publishing = !saveAsDraft;
        providerEligibilityChecker.assertEligibleToManageListing(providerUserId, publishing);
        checkCreateRateLimit(providerUserId);

        CategorySnapshot category = resolveActiveCategoryHierarchy(request.categoryId());
        Instant now = Instant.now();

        ServiceListing listing = new ServiceListing();
        listing.setProviderId(providerUserId);
        listing.setCategoryId(category.id());
        listing.setRatingAvg(0.0);
        listing.setRatingCount(0);
        listing.setCreatedAt(now);
        listing.setUpdatedAt(now);

        applyValidatedPayload(
                listing,
                request.title(),
                request.description(),
                request.pricingModel(),
                request.basePrice(),
                request.durationMin(),
                request.executionModel(),
                request.district(),
                request.baseCity(),
                request.location(),
                request.coverageRadiusKm(),
                request.serviceAreas(),
                request.images(),
                request.coverImageUrl(),
                request.addOns(),
                publishing
        );

        if (saveAsDraft) {
            listing.setStatus(ListingStatus.DRAFT);
            listing.setModerationFlags(List.of());
            listing.setModerationReason(null);
        } else {
            applyPublishScreening(listing, ListingStatus.ACTIVE);
        }

        ServiceListing saved = listingRepository.save(listing);
        return listingMapper.toResponse(saved, category, true);
    }

    @Override
    public Page<ListingSummaryResponse> getMyListings(
            String providerUserId,
            ListingStatus status,
            String search,
            Pageable pageable
    ) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new AccessDeniedException("Authentication is required to view your listings");
        }
        String trimmedSearch = search == null ? "" : search.trim();
        Page<ServiceListing> page;
        if (trimmedSearch.isEmpty()) {
            page = status == null
                    ? listingRepository.findByProviderIdOrderByUpdatedAtDesc(providerUserId, pageable)
                    : listingRepository.findByProviderIdAndStatusOrderByUpdatedAtDesc(providerUserId, status, pageable);
        } else {
            page = status == null
                    ? listingRepository.findByProviderIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
                            providerUserId, trimmedSearch, pageable)
                    : listingRepository.findByProviderIdAndStatusAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
                            providerUserId, status, trimmedSearch, pageable);
        }

        Map<String, CategorySnapshot> categories = resolveEffectiveCategoriesForPage(page.getContent());
        return page.map(item -> listingMapper.toSummaryResponse(
                item, categories.get(item.getCategoryId()), true));
    }

    @Override
    public ProviderListingDashboardSummaryResponse getMyListingsSummary(String providerUserId) {
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new AccessDeniedException("Authentication is required to view listing summary");
        }
        List<ServiceListing> all = listingRepository.findByProviderIdOrderByUpdatedAtDesc(providerUserId);
        Map<String, CategorySnapshot> categories = resolveEffectiveCategoriesForPage(all);

        long active = 0;
        long paused = 0;
        long draft = 0;
        long archived = 0;
        long flagged = 0;
        long rejected = 0;
        long categoryInactive = 0;

        for (ServiceListing item : all) {
            switch (item.getStatus()) {
                case ACTIVE -> active++;
                case PAUSED -> paused++;
                case DRAFT -> draft++;
                case ARCHIVED -> archived++;
                case FLAGGED -> flagged++;
                case REJECTED -> rejected++;
            }
            CategorySnapshot cat = categories.get(item.getCategoryId());
            if (cat == null || !cat.active()) {
                categoryInactive++;
            }
        }

        return new ProviderListingDashboardSummaryResponse(
                all.size(),
                active,
                paused,
                draft,
                archived,
                flagged,
                rejected,
                categoryInactive
        );
    }

    @Override
    public ListingResponse getListingById(String id) {
        return getListingForViewer(id, CurrentUser.getUserId(), CurrentUser.getRole());
    }

    @Override
    public List<ListingResponse> getListingsByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                orderedIds.add(id.trim());
            }
        }
        if (orderedIds.isEmpty()) {
            return List.of();
        }

        List<ServiceListing> found = listingRepository.findAllById(orderedIds);
        Map<String, ServiceListing> byId = new HashMap<>();
        for (ServiceListing listing : found) {
            if (listing.getStatus().isFavouriteReadable()) {
                byId.put(listing.getId(), listing);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }

        Map<String, CategorySnapshot> categories = resolveEffectiveCategoriesForPage(new ArrayList<>(byId.values()));
        List<ListingResponse> responses = new ArrayList<>();
        for (String id : orderedIds) {
            ServiceListing listing = byId.get(id);
            if (listing != null) {
                responses.add(listingMapper.toResponse(
                        listing,
                        categories.get(listing.getCategoryId()),
                        false
                ));
            }
        }
        return responses;
    }

    @Override
    public ListingResponse getListingForViewer(String id, String currentUserIdOrNull, Role currentRoleOrNull) {
        ServiceListing listing = findEntityOrThrow(id);
        boolean isOwner = currentUserIdOrNull != null && currentUserIdOrNull.equals(listing.getProviderId());
        boolean isModeratorViewer = currentRoleOrNull == Role.MODERATOR || currentRoleOrNull == Role.SUPER_ADMIN;

        if (!listing.getStatus().isFavouriteReadable() && !isOwner && !isModeratorViewer) {
            throw new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found");
        }

        CategorySnapshot category = resolveEffectiveCategory(listing.getCategoryId());
        return listingMapper.toResponse(listing, category, isOwner || isModeratorViewer);
    }

    @Override
    public ListingResponse updateListing(String providerUserId, String id, UpdateListingRequest request) {
        ServiceListing existing = findEntityOrThrow(id);
        OwnershipChecker.assertOwner(providerUserId, existing.getProviderId());

        if (!existing.getStatus().isProviderEditable()) {
            throw new ResourceConflictException("LISTING_ARCHIVED", "Archived listings cannot be modified");
        }

        ListingStatus currentStatus = existing.getStatus();
        if (currentStatus != ListingStatus.DRAFT && Boolean.TRUE.equals(request.saveAsDraft())) {
            throw new ResourceConflictException(
                    "INVALID_STATUS_TRANSITION",
                    "Published or moderated listings cannot revert to DRAFT"
            );
        }

        boolean keepAsDraft = currentStatus == ListingStatus.DRAFT && !Boolean.FALSE.equals(request.saveAsDraft());
        boolean publishing = !keepAsDraft;
        providerEligibilityChecker.assertEligibleToManageListing(providerUserId, publishing);

        CategorySnapshot category = resolveActiveCategoryHierarchy(request.categoryId());
        existing.setCategoryId(category.id());

        applyValidatedPayload(
                existing,
                request.title(),
                request.description(),
                request.pricingModel(),
                request.basePrice(),
                request.durationMin(),
                request.executionModel(),
                request.district(),
                request.baseCity(),
                request.location(),
                request.coverageRadiusKm(),
                request.serviceAreas(),
                request.images(),
                request.coverImageUrl(),
                request.addOns(),
                publishing
        );

        applyEditStatusAndScreening(existing, currentStatus, keepAsDraft);
        existing.setUpdatedAt(Instant.now());

        ServiceListing saved = listingRepository.save(existing);
        return listingMapper.toResponse(saved, category, true);
    }

    @Override
    public ListingResponse updateListingStatus(
            String providerUserId,
            String id,
            UpdateListingStatusRequest request
    ) {
        ServiceListing existing = findEntityOrThrow(id);
        OwnershipChecker.assertOwner(providerUserId, existing.getProviderId());

        ListingStatus current = existing.getStatus();
        ListingStatus target = request.status();
        if (!current.canProviderTransitionTo(target)) {
            throw new ResourceConflictException(
                    "INVALID_STATUS_TRANSITION",
                    "Listing cannot transition from " + current + " to " + target
            );
        }

        CategorySnapshot category = resolveEffectiveCategory(existing.getCategoryId());
        if (target == ListingStatus.ACTIVE) {
            providerEligibilityChecker.assertEligibleToManageListing(providerUserId, true);
            category = resolveActiveCategoryHierarchy(existing.getCategoryId());
            validatePublishPrerequisitesForExisting(existing);
            applyPublishScreening(existing, ListingStatus.ACTIVE);
            existing.setHiddenByProviderSuspension(false);
        } else {
            existing.setStatus(target);
            if (target == ListingStatus.ARCHIVED) {
                existing.setHiddenByProviderSuspension(false);
            }
        }

        existing.setUpdatedAt(Instant.now());
        ServiceListing saved = listingRepository.save(existing);
        return listingMapper.toResponse(saved, category, true);
    }

    @Override
    public ListingResponse getListingForModeration(String id) {
        ServiceListing listing = findEntityOrThrow(id);
        CategorySnapshot category = resolveEffectiveCategory(listing.getCategoryId());
        return listingMapper.toResponse(listing, category, true);
    }

    @Override
    public Page<ListingSummaryResponse> getModerationQueue(ListingStatus status, Pageable pageable) {
        ListingStatus effectiveStatus = status == null ? ListingStatus.FLAGGED : status;
        Page<ServiceListing> page = listingRepository.findByStatusOrderByCreatedAtAsc(effectiveStatus, pageable);
        Map<String, CategorySnapshot> categories = resolveEffectiveCategoriesForPage(page.getContent());
        return page.map(item -> listingMapper.toSummaryResponse(
                item, categories.get(item.getCategoryId()), true));
    }

    @Override
    public ListingResponse moderateListing(
            String moderatorUserId,
            String id,
            ListingModerationRequest request
    ) {
        if (moderatorUserId == null || moderatorUserId.isBlank()) {
            throw new AccessDeniedException("Moderator identity could not be resolved");
        }

        ServiceListing existing = findEntityOrThrow(id);
        ListingStatus targetStatus = request.decision() == ModerationDecision.APPROVE
                ? ListingStatus.ACTIVE
                : ListingStatus.REJECTED;

        if (!existing.getStatus().canModeratorTransitionTo(targetStatus)) {
            throw new ResourceConflictException(
                    "INVALID_MODERATION_STATE",
                    "Only FLAGGED listings can be moderated cleanly; current status is " + existing.getStatus()
            );
        }

        String trimmedReason = request.reason() == null ? null : request.reason().trim();
        if (request.decision() == ModerationDecision.REJECT && (trimmedReason == null || trimmedReason.isEmpty())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MODERATION_REASON_REQUIRED",
                    "A non-blank reason is required when rejecting a flagged listing"
            );
        }

        CategorySnapshot category;
        if (targetStatus == ListingStatus.ACTIVE) {
            providerEligibilityChecker.assertEligibleToManageListing(existing.getProviderId(), true);
            category = resolveActiveCategoryHierarchy(existing.getCategoryId());
            existing.setHiddenByProviderSuspension(false);
            existing.setModerationFlags(List.of());
            existing.setModerationReason(trimmedReason == null || trimmedReason.isEmpty() ? null : trimmedReason);
        } else {
            category = resolveEffectiveCategory(existing.getCategoryId());
            existing.setModerationReason(trimmedReason);
        }

        Instant now = Instant.now();
        existing.setStatus(targetStatus);
        existing.setModeratedBy(moderatorUserId);
        existing.setModeratedAt(now);
        existing.setUpdatedAt(now);

        ServiceListing saved = listingRepository.save(existing);
        return listingMapper.toResponse(saved, category, true);
    }

    @Override
    public PriceSnapshotResponse getListingPriceSnapshot(String listingId, List<String> selectedAddOnIds) {
        ServiceListing listing = findEntityOrThrow(listingId);
        if (listing.getStatus() != ListingStatus.ACTIVE || listing.isHiddenByProviderSuspension()) {
            throw new ResourceConflictException(
                    "LISTING_NOT_BOOKABLE",
                    "Price snapshots can only be captured for active available listings"
            );
        }

        CategorySnapshot category = resolveActiveCategoryHierarchy(listing.getCategoryId());
        BigDecimal commissionRate = category.commissionRate() != null
                ? category.commissionRate()
                : BigDecimal.ZERO;

        List<AddOnItemResponse> selectedAddOnResponses = resolveSelectedAddOns(listing, selectedAddOnIds);

        BigDecimal totalPrice = listing.getBasePrice();
        int totalDuration = listing.getDurationMin();
        for (AddOnItemResponse addOn : selectedAddOnResponses) {
            totalPrice = totalPrice.add(addOn.extraPrice());
            totalDuration += addOn.extraDurationMin();
        }

        return new PriceSnapshotResponse(
                listing.getId(),
                listing.getProviderId(),
                listing.getCategoryId(),
                listing.getTitle(),
                listing.getPricingModel(),
                listing.getBasePrice(),
                listing.getDurationMin(),
                commissionRate,
                selectedAddOnResponses,
                totalPrice,
                totalDuration,
                Instant.now()
        );
    }

    @Override
    public void updateProviderSuspensionVisibility(String providerId, boolean suspended) {
        Instant now = Instant.now();
        if (suspended) {
            List<ServiceListing> activeListings =
                    listingRepository.findByProviderIdAndStatus(providerId, ListingStatus.ACTIVE);
            for (ServiceListing listing : activeListings) {
                listing.setStatus(ListingStatus.PAUSED);
                listing.setHiddenByProviderSuspension(true);
                listing.setUpdatedAt(now);
            }
            if (!activeListings.isEmpty()) {
                listingRepository.saveAll(activeListings);
            }
            return;
        }

        List<ServiceListing> hiddenListings =
                listingRepository.findByProviderIdAndHiddenByProviderSuspensionTrue(providerId);
        for (ServiceListing listing : hiddenListings) {
            listing.setHiddenByProviderSuspension(false);
            if (listing.getStatus() == ListingStatus.PAUSED) {
                listing.setStatus(ListingStatus.ACTIVE);
            }
            listing.setUpdatedAt(now);
        }
        if (!hiddenListings.isEmpty()) {
            listingRepository.saveAll(hiddenListings);
        }
    }

    @Override
    public void updateListingRatingStats(String listingId, double ratingAvg, int ratingCount) {
        if (Double.isNaN(ratingAvg) || ratingAvg < 0.0 || ratingAvg > 5.0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_RATING_AVERAGE",
                    "Rating average must be between 0.0 and 5.0"
            );
        }
        if (ratingCount < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_RATING_COUNT",
                    "Rating count must not be negative"
            );
        }

        ServiceListing listing = findEntityOrThrow(listingId);
        listing.setRatingAvg(ratingAvg);
        listing.setRatingCount(ratingCount);
        listing.setUpdatedAt(Instant.now());
        listingRepository.save(listing);
    }

    @Override
    public boolean hasBlockingListingsForCategory(String categoryId) {
        return listingRepository.existsByCategoryIdAndStatusIn(categoryId, ListingStatus.CATEGORY_BLOCKING_STATUSES);
    }

    @Override
    public List<ProvinceGeoDto> getDistrictLocations() {
        return districtGeoService.getProvincesWithCoordinates();
    }

    private void checkCreateRateLimit(String providerUserId) {
        if (rateLimitServiceProvider == null) {
            return;
        }
        RateLimitService rateLimitService = rateLimitServiceProvider.getIfAvailable();
        if (rateLimitService != null) {
            rateLimitService.check(
                    BUCKET_LISTING_CREATE,
                    providerUserId,
                    CREATE_RATE_LIMIT_MAX_REQUESTS,
                    CREATE_RATE_LIMIT_WINDOW
            );
        }
    }

    private ServiceListing findEntityOrThrow(String id) {
        return listingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found with id: " + id));
    }

    private CategoryLookupPort requireCategoryPort() {
        CategoryLookupPort port = categoryLookupPortProvider.getIfAvailable();
        if (port == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CATEGORY_LOOKUP_UNAVAILABLE",
                    "Category verification is temporarily unavailable"
            );
        }
        return port;
    }

    private CategorySnapshot resolveActiveCategoryHierarchy(String categoryId) {
        CategoryLookupPort port = requireCategoryPort();
        CategorySnapshot category = port.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CATEGORY_NOT_FOUND", "Category not found with id: " + categoryId));

        if (!category.active()) {
            throw new ResourceConflictException(
                    "CATEGORY_INACTIVE",
                    "Listings cannot use an inactive category"
            );
        }

        if (category.parentId() != null && !category.parentId().isBlank()) {
            CategorySnapshot parent = port.findById(category.parentId())
                    .orElseThrow(() -> new ResourceConflictException(
                            "PARENT_CATEGORY_UNAVAILABLE",
                            "Parent category for '" + category.name() + "' could not be found"
                    ));
            if (!parent.active()) {
                throw new ResourceConflictException(
                        "PARENT_CATEGORY_INACTIVE",
                        "Listings cannot use a subcategory whose parent category is inactive"
                );
            }
        }
        return category;
    }

    private CategorySnapshot resolveEffectiveCategory(String categoryId) {
        CategoryLookupPort port = categoryLookupPortProvider != null
                ? categoryLookupPortProvider.getIfAvailable()
                : null;
        if (port == null || categoryId == null) {
            return null;
        }
        CategorySnapshot category = port.findById(categoryId).orElse(null);
        return applyParentActiveStatus(port, category, new HashMap<>());
    }

    private Map<String, CategorySnapshot> resolveEffectiveCategoriesForPage(List<ServiceListing> listings) {
        CategoryLookupPort port = categoryLookupPortProvider != null
                ? categoryLookupPortProvider.getIfAvailable()
                : null;
        Map<String, CategorySnapshot> result = new HashMap<>();
        if (port == null || listings == null || listings.isEmpty()) {
            return result;
        }
        Map<String, CategorySnapshot> rawCache = new HashMap<>();
        for (ServiceListing listing : listings) {
            String categoryId = listing.getCategoryId();
            if (categoryId != null && !result.containsKey(categoryId)) {
                CategorySnapshot raw = rawCache.computeIfAbsent(categoryId, id -> port.findById(id).orElse(null));
                result.put(categoryId, applyParentActiveStatus(port, raw, rawCache));
            }
        }
        return result;
    }

    private CategorySnapshot applyParentActiveStatus(
            CategoryLookupPort port,
            CategorySnapshot category,
            Map<String, CategorySnapshot> rawCache
    ) {
        if (category == null || !category.active() || category.parentId() == null || category.parentId().isBlank()) {
            return category;
        }
        CategorySnapshot parent = rawCache.computeIfAbsent(
                category.parentId(), id -> port.findById(id).orElse(null));
        if (parent == null || !parent.active()) {
            return new CategorySnapshot(
                    category.id(),
                    category.name(),
                    category.parentId(),
                    false,
                    category.commissionRate()
            );
        }
        return category;
    }

    private String resolveAndValidateDistrict(String rawDistrict) {
        return districtGeoService.resolveCanonicalDistrict(rawDistrict)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_DISTRICT",
                        "District '" + (rawDistrict == null ? "" : rawDistrict.trim()) + "' is not a valid Sri Lankan district"
                ));
    }

    private void validateLocationBounds(GeoPointDto location) {
        if (!districtGeoService.isWithinSriLankaBounds(location)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_LOCATION_BOUNDS",
                    "Location coordinates must fall within Sri Lanka bounds"
            );
        }
    }

    private void applyValidatedPayload(
            ServiceListing target,
            String rawTitle,
            String rawDescription,
            PricingModel pricingModel,
            BigDecimal basePrice,
            Integer durationMin,
            ExecutionModel executionModel,
            String rawDistrict,
            String rawBaseCity,
            GeoPointDto location,
            Integer coverageRadiusKm,
            List<String> rawServiceAreas,
            List<String> rawImages,
            String rawCoverImageUrl,
            List<AddOnItemRequest> rawAddOns,
            boolean publishing
    ) {
        String canonicalDistrict = resolveAndValidateDistrict(rawDistrict);
        List<String> serviceAreas = normalizeServiceAreas(rawServiceAreas);
        String trimmedTitle = rawTitle == null ? "" : rawTitle.trim();
        String trimmedDescription = rawDescription == null ? null : rawDescription.trim();
        String trimmedBaseCity = rawBaseCity == null ? "" : rawBaseCity.trim();

        if (publishing) {
            validatePublishRequiredFields(
                    trimmedTitle,
                    trimmedDescription,
                    pricingModel,
                    basePrice,
                    durationMin,
                    executionModel,
                    trimmedBaseCity,
                    location,
                    coverageRadiusKm,
                    serviceAreas
            );
        }

        GeoPointDto effectiveLocation = location;
        if (effectiveLocation == null && !publishing) {
            effectiveLocation = districtGeoService.getDistrictCenter(canonicalDistrict);
        } else if (effectiveLocation != null) {
            validateLocationBounds(effectiveLocation);
        }

        ExecutionModel effectiveExecutionModel = executionModel != null ? executionModel : ExecutionModel.ON_SITE;
        Integer effectiveCoverageRadiusKm = coverageRadiusKm != null ? coverageRadiusKm : DEFAULT_DRAFT_COVERAGE_RADIUS_KM;

        ValidatedMedia media = mediaValidator.validate(rawImages, rawCoverImageUrl, publishing);
        List<AddOnItem> addOns = normalizeAddOns(rawAddOns);

        target.setTitle(trimmedTitle);
        target.setDescription(trimmedDescription);
        target.setPricingModel(pricingModel);
        target.setBasePrice(basePrice);
        target.setDurationMin(durationMin);
        target.setExecutionModel(effectiveExecutionModel);
        target.setDistrict(canonicalDistrict);
        target.setBaseCity(trimmedBaseCity);
        target.setLocation(listingMapper.toGeoJsonPoint(effectiveLocation));
        target.setCoverageRadiusKm(effectiveCoverageRadiusKm);
        target.setServiceAreas(serviceAreas);
        target.setImages(media.images());
        target.setCoverImageUrl(media.coverImageUrl());
        target.setAddOns(addOns);
    }

    private void validatePublishPrerequisitesForExisting(ServiceListing existing) {
        resolveAndValidateDistrict(existing.getDistrict());
        GeoPointDto point = listingMapper.toGeoPointDto(existing.getLocation());
        validatePublishRequiredFields(
                existing.getTitle(),
                existing.getDescription(),
                existing.getPricingModel(),
                existing.getBasePrice(),
                existing.getDurationMin(),
                existing.getExecutionModel(),
                existing.getBaseCity(),
                point,
                existing.getCoverageRadiusKm(),
                existing.getServiceAreas()
        );
        validateLocationBounds(point);
        mediaValidator.validate(existing.getImages(), existing.getCoverImageUrl(), true);
    }

    private void validatePublishRequiredFields(
            String title,
            String description,
            PricingModel pricingModel,
            BigDecimal basePrice,
            Integer durationMin,
            ExecutionModel executionModel,
            String baseCity,
            GeoPointDto location,
            Integer coverageRadiusKm,
            List<String> serviceAreas
    ) {
        if (title == null || title.trim().length() < MIN_PUBLISH_TITLE_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Title must be at least " + MIN_PUBLISH_TITLE_LENGTH + " characters when publishing"
            );
        }
        if (description == null || description.trim().length() < MIN_PUBLISH_DESCRIPTION_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Description must be at least " + MIN_PUBLISH_DESCRIPTION_LENGTH + " characters when publishing"
            );
        }
        if (pricingModel == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Pricing model is required when publishing");
        }
        if (basePrice == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Base price is required when publishing");
        }
        if (durationMin == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Duration is required when publishing");
        }
        if (executionModel == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Execution model is required when publishing");
        }
        if (baseCity == null || baseCity.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Base city is required when publishing");
        }
        if (location == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Location coordinates are required when publishing");
        }
        if (coverageRadiusKm == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Coverage radius is required when publishing");
        }
        if (serviceAreas == null || serviceAreas.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "At least 1 service area is required when publishing"
            );
        }
    }

    private void applyEditStatusAndScreening(
            ServiceListing listing,
            ListingStatus previousStatus,
            boolean keepAsDraft
    ) {
        if (keepAsDraft) {
            listing.setStatus(ListingStatus.DRAFT);
            listing.setModerationFlags(List.of());
            listing.setModerationReason(null);
            return;
        }

        if (previousStatus == ListingStatus.FLAGGED || previousStatus == ListingStatus.REJECTED) {
            ScreeningResult screening = screenListingText(listing);
            listing.setStatus(ListingStatus.FLAGGED);
            if (screening.flagged()) {
                listing.setModerationFlags(screening.flagReasons());
                listing.setModerationReason(String.join("; ", screening.flagReasons()));
            } else {
                listing.setModerationFlags(List.of());
                listing.setModerationReason("Updated by provider; awaiting moderator review");
            }
            return;
        }

        ListingStatus cleanTargetStatus = previousStatus == ListingStatus.PAUSED
                ? ListingStatus.PAUSED
                : ListingStatus.ACTIVE;
        applyPublishScreening(listing, cleanTargetStatus);
    }

    private void applyPublishScreening(ServiceListing listing, ListingStatus cleanTargetStatus) {
        ScreeningResult screening = screenListingText(listing);
        if (screening.flagged()) {
            listing.setStatus(ListingStatus.FLAGGED);
            listing.setModerationFlags(screening.flagReasons());
            listing.setModerationReason(String.join("; ", screening.flagReasons()));
            return;
        }

        listing.setStatus(cleanTargetStatus);
        listing.setModerationFlags(List.of());
        if (listing.getModeratedBy() == null) {
            listing.setModerationReason(null);
        }
    }

    private ScreeningResult screenListingText(ServiceListing listing) {
        List<String> addOnTitles = listing.getAddOns() == null
                ? List.of()
                : listing.getAddOns().stream()
                        .map(AddOnItem::getTitle)
                        .filter(t -> t != null && !t.isBlank())
                        .toList();
        List<String> addOnDescriptions = listing.getAddOns() == null
                ? List.of()
                : listing.getAddOns().stream()
                        .map(AddOnItem::getDescription)
                        .filter(d -> d != null && !d.isBlank())
                        .toList();
        return contentScreeningService.screen(
                listing.getTitle(),
                listing.getDescription(),
                addOnTitles,
                addOnDescriptions
        );
    }

    private List<String> normalizeServiceAreas(List<String> rawAreas) {
        if (rawAreas == null || rawAreas.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String area : rawAreas) {
            if (area != null && !area.isBlank()) {
                unique.add(area.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    private List<AddOnItem> normalizeAddOns(List<AddOnItemRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<AddOnItem> normalized = new ArrayList<>();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        for (AddOnItemRequest req : requests) {
            String addOnId = (req.addOnId() == null || req.addOnId().isBlank())
                    ? UUID.randomUUID().toString()
                    : req.addOnId().trim();
            if (!seenIds.add(addOnId)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "DUPLICATE_ADD_ON_ID",
                        "Duplicate addOnId in listing payload: " + addOnId
                );
            }
            boolean active = req.active() == null || req.active();
            String description = req.description() == null || req.description().isBlank()
                    ? null
                    : req.description().trim();
            normalized.add(new AddOnItem(
                    addOnId,
                    req.title().trim(),
                    description,
                    req.extraPrice(),
                    req.extraDurationMin(),
                    active
            ));
        }
        return normalized;
    }

    private List<AddOnItemResponse> resolveSelectedAddOns(ServiceListing listing, List<String> selectedAddOnIds) {
        if (selectedAddOnIds == null || selectedAddOnIds.isEmpty()) {
            return List.of();
        }

        Map<String, AddOnItem> byId = new LinkedHashMap<>();
        if (listing.getAddOns() != null) {
            for (AddOnItem item : listing.getAddOns()) {
                byId.put(item.getAddOnId(), item);
            }
        }

        LinkedHashSet<String> requestedIds = new LinkedHashSet<>();
        for (String rawId : selectedAddOnIds) {
            if (rawId == null || rawId.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADD_ON_ID", "Selected add-on ID must not be blank");
            }
            requestedIds.add(rawId.trim());
        }

        List<AddOnItemResponse> selected = new ArrayList<>();
        for (String addOnId : requestedIds) {
            AddOnItem item = byId.get(addOnId);
            if (item == null) {
                throw new ResourceNotFoundException("ADD_ON_NOT_FOUND", "Add-on not found on listing: " + addOnId);
            }
            if (!item.isActive()) {
                throw new ResourceConflictException("ADD_ON_INACTIVE", "Add-on is inactive: " + addOnId);
            }
            selected.add(listingMapper.toAddOnItemResponse(item));
        }
        return selected;
    }
}
