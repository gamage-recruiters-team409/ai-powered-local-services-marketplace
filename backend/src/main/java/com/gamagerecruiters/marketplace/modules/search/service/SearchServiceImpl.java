package com.gamagerecruiters.marketplace.modules.search.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicPricingResponse;
import com.gamagerecruiters.marketplace.modules.search.dto.ProviderSearchResult;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchRequest;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchResponse;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchSupportedFlags;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

@Service
public class SearchServiceImpl implements SearchService {

    private static final int MAX_TEXT_LENGTH = 120;
    private static final int MAX_CATEGORY_LENGTH = 100;
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 12;
    private static final int MAX_SIZE = 24;
    private static final String DEFAULT_SORT = "recommended";
    private static final Set<String> SUPPORTED_SORTS = Set.of("recommended", "price_asc", "price_desc");
    private static final Set<String> SUPPORTED_PARAMS = Set.of(
            "q", "location", "category", "maxPrice", "verified", "sort", "page", "size");

    private final ListingService listingService;
    private final ProviderProfileRepository providerProfileRepository;
    private final ReviewService reviewService;
    private final ProviderVerificationRepository verificationRepository;

    public SearchServiceImpl(
            ListingService listingService,
            ProviderProfileRepository providerProfileRepository,
            ReviewService reviewService,
            ProviderVerificationRepository verificationRepository
    ) {
        this.listingService = listingService;
        this.providerProfileRepository = providerProfileRepository;
        this.reviewService = reviewService;
        this.verificationRepository = verificationRepository;
    }

    @Override
    public SearchResponse search(SearchRequest request) {
        rejectUnsupportedFilters(request);
        validateRequest(request);

        String sort = normalizeSort(request.sort());
        Pageable pageable = PageRequest.of(request.page(), request.size(), pageSort(sort));
        Collection<String> verifiedProviderIds = request.verified()
                ? findApprovedProviderIds()
                : null;

        if (request.verified() && (verifiedProviderIds == null || verifiedProviderIds.isEmpty())) {
            return new SearchResponse(
                    List.of(),
                    request.page(),
                    request.size(),
                    0L,
                    List.of()
            );
        }

        Page<ListingResponse> listingPage = listingService.searchPublicListings(
                trimToNull(request.q()),
                trimToNull(request.location()),
                trimToNull(request.category()),
                request.maxPrice(),
                verifiedProviderIds,
                pageable
        );

        List<ListingResponse> listings = listingPage.getContent();
        Set<String> providerIds = distinctProviderIds(listings);
        Map<String, ProviderProfile> profiles = findProfiles(providerIds);
        Map<String, ProviderVerification> verifications = findVerifications(providerIds);
        Map<String, RatingSummaryResponse> ratings = reviewService.getRatingSummaries(providerIds);

        List<ProviderSearchResult> results = new ArrayList<>(listings.size());
        for (ListingResponse listing : listings) {
            String providerId = listing.providerId();
            ProviderProfile profile = profiles.get(providerId);
            ProviderVerification verification = verifications.get(providerId);
            RatingSummaryResponse summary = ratings.get(providerId);
            results.add(new ProviderSearchResult(
                    providerId,
                    profile == null ? null : profile.getId(),
                    listing.id(),
                    displayName(profile, listing),
                    listingImage(listing, profile),
                    isApproved(verification),
                    rating(summary),
                    reviewCount(summary),
                    listing.categoryName(),
                    listing.description(),
                    location(listing, profile),
                    pricing(listing),
                    listing.coverageRadiusKm(),
                    SearchSupportedFlags.NONE
            ));
        }

        return new SearchResponse(
                results,
                listingPage.getNumber(),
                listingPage.getSize(),
                listingPage.getTotalElements(),
                List.of()
        );
    }

    private void rejectUnsupportedFilters(SearchRequest request) {
        Set<String> present = request.presentParams();
        List<String> unsupported = new ArrayList<>();
        for (String name : present) {
            if (!SUPPORTED_PARAMS.contains(name)) {
                unsupported.add(name);
            }
        }
        if ("rating".equals(trimToNull(request.sort()))) {
            unsupported.add("sort=rating");
        }
        if (unsupported.isEmpty()) {
            return;
        }
        unsupported.sort(String::compareTo);
        throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_SEARCH_FILTER",
                "Unsupported search filters: " + String.join(", ", unsupported)
        );
    }

    private void validateRequest(SearchRequest request) {
        validateLength(request.q(), MAX_TEXT_LENGTH, "q");
        validateLength(request.location(), MAX_TEXT_LENGTH, "location");
        validateLength(request.category(), MAX_CATEGORY_LENGTH, "category");

        if (request.maxPrice() != null && request.maxPrice().signum() < 0) {
            throw badRequest("INVALID_MAX_PRICE", "maxPrice must not be negative");
        }
        if (request.page() < DEFAULT_PAGE) {
            throw badRequest("INVALID_PAGE", "page must be greater than or equal to 0");
        }
        if (request.size() < 1 || request.size() > MAX_SIZE) {
            throw badRequest("INVALID_SIZE", "size must be between 1 and " + MAX_SIZE);
        }
    }

    private void validateLength(String value, int maxLength, String paramName) {
        if (value != null && value.length() > maxLength) {
            throw badRequest(
                    "INVALID_SEARCH_PARAM",
                    paramName + " must not exceed " + maxLength + " characters"
            );
        }
    }

    private String normalizeSort(String rawSort) {
        String sort = trimToNull(rawSort);
        if (sort == null) {
            return DEFAULT_SORT;
        }
        if (!SUPPORTED_SORTS.contains(sort)) {
            throw badRequest("INVALID_SORT", "Unsupported sort: " + sort);
        }
        return sort;
    }

    private Sort pageSort(String sort) {
        return switch (sort) {
            case "price_asc" -> Sort.by(Sort.Order.asc("basePrice"), Sort.Order.asc("id"));
            case "price_desc" -> Sort.by(Sort.Order.desc("basePrice"), Sort.Order.asc("id"));
            default -> Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"));
        };
    }

    private Collection<String> findApprovedProviderIds() {
        Set<String> approvedIds = new LinkedHashSet<>();
        List<ProviderVerificationRepository.ProviderIdOnly> approved =
                verificationRepository.findProviderIdsByStatus(ProviderVerificationStatus.APPROVED);
        if (approved == null) {
            return approvedIds;
        }
        for (ProviderVerificationRepository.ProviderIdOnly verification : approved) {
            if (verification != null && verification.getProviderId() != null
                    && !verification.getProviderId().isBlank()) {
                approvedIds.add(verification.getProviderId());
            }
        }
        return approvedIds;
    }

    private Set<String> distinctProviderIds(List<ListingResponse> listings) {
        Set<String> ids = new LinkedHashSet<>();
        for (ListingResponse listing : listings) {
            if (listing.providerId() != null && !listing.providerId().isBlank()) {
                ids.add(listing.providerId());
            }
        }
        return ids;
    }

    private Map<String, ProviderProfile> findProfiles(Set<String> providerIds) {
        Map<String, ProviderProfile> profiles = new LinkedHashMap<>();
        if (providerIds.isEmpty()) {
            return profiles;
        }
        List<ProviderProfile> found = providerProfileRepository.findAllByUserIdIn(providerIds);
        if (found == null) {
            return profiles;
        }
        for (ProviderProfile profile : found) {
            if (profile != null && profile.getUserId() != null) {
                profiles.put(profile.getUserId(), profile);
            }
        }
        return profiles;
    }

    private Map<String, ProviderVerification> findVerifications(Set<String> providerIds) {
        Map<String, ProviderVerification> verifications = new LinkedHashMap<>();
        if (providerIds.isEmpty()) {
            return verifications;
        }
        List<ProviderVerification> found = verificationRepository.findAllByProviderIdIn(providerIds);
        if (found == null) {
            return verifications;
        }
        for (ProviderVerification verification : found) {
            if (verification != null && verification.getProviderId() != null) {
                verifications.put(verification.getProviderId(), verification);
            }
        }
        return verifications;
    }

    private boolean isApproved(ProviderVerification verification) {
        return verification != null && verification.getStatus() == ProviderVerificationStatus.APPROVED;
    }

    private Double rating(RatingSummaryResponse summary) {
        if (summary == null || summary.reviewCount() == 0) {
            return null;
        }
        return summary.averageRating();
    }

    private Long reviewCount(RatingSummaryResponse summary) {
        return summary == null ? null : summary.reviewCount();
    }

    private String displayName(ProviderProfile profile, ListingResponse listing) {
        if (profile != null && hasText(profile.getDisplayName())) {
            return profile.getDisplayName();
        }
        return listing.title();
    }

    private String listingImage(ListingResponse listing, ProviderProfile profile) {
        if (hasText(listing.coverImageUrl())) {
            return listing.coverImageUrl();
        }
        if (listing.images() != null) {
            for (String image : listing.images()) {
                if (hasText(image)) {
                    return image;
                }
            }
        }
        return profile == null ? null : profile.getProfileImageUrl();
    }

    private String location(ListingResponse listing, ProviderProfile profile) {
        String listingLocation = joinLocation(listing.district(), listing.baseCity());
        if (listingLocation != null) {
            return listingLocation;
        }
        return profile == null ? null : joinLocation(profile.getDistrict(), profile.getCity());
    }

    private String joinLocation(String first, String second) {
        Set<String> parts = new LinkedHashSet<>();
        if (hasText(first)) {
            parts.add(first.trim());
        }
        if (hasText(second)) {
            parts.add(second.trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private PublicPricingResponse pricing(ListingResponse listing) {
        PricingModel model = listing.pricingModel();
        return PublicPricingResponse.from(listing.basePrice(), model, listing.durationMin());
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
