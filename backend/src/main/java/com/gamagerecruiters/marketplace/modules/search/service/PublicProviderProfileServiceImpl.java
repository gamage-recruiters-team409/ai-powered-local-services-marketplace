package com.gamagerecruiters.marketplace.modules.search.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort.CategorySnapshot;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicPricingResponse;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicProfileSupportedFlags;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicProviderListingSummary;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicProviderReviewResponse;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

@Service
public class PublicProviderProfileServiceImpl implements PublicProviderProfileService {

    private static final Logger log = LoggerFactory.getLogger(PublicProviderProfileServiceImpl.class);

    private static final Comparator<ListingResponse> BEST_LISTING = Comparator
            .comparing(ListingResponse::basePrice, Comparator.nullsLast(BigDecimal::compareTo))
            .thenComparing(ListingResponse::id, Comparator.nullsLast(String::compareTo));
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final String REVIEW_CONTENT_AVAILABLE = "AVAILABLE";
    private static final String REVIEW_CONTENT_UNAVAILABLE = "UNAVAILABLE";
    private static final int REVIEW_PAGE_SIZE = 5;

    private final ListingService listingService;
    private final ProviderProfileRepository providerProfileRepository;
    private final ReviewService reviewService;
    private final ProviderVerificationRepository verificationRepository;
    private final ObjectProvider<CategoryLookupPort> categoryLookupPortProvider;

    public PublicProviderProfileServiceImpl(
            ListingService listingService,
            ProviderProfileRepository providerProfileRepository,
            ReviewService reviewService,
            ProviderVerificationRepository verificationRepository,
            ObjectProvider<CategoryLookupPort> categoryLookupPortProvider
    ) {
        this.listingService = listingService;
        this.providerProfileRepository = providerProfileRepository;
        this.reviewService = reviewService;
        this.verificationRepository = verificationRepository;
        this.categoryLookupPortProvider = categoryLookupPortProvider;
    }

    @Override
    public PublicProviderProfileResponse getPublicProfile(String providerIdOrProfileId) {
        return getPublicProfile(providerIdOrProfileId, 0);
    }

    @Override
    public PublicProviderProfileResponse getPublicProfile(String providerIdOrProfileId, int reviewPage) {
        ProviderProfile profile = resolveProfile(providerIdOrProfileId);
        String canonicalProviderId = hasText(profile.getUserId()) ? profile.getUserId() : profile.getId();

        List<ListingResponse> listings = listingService.getPublicListingsByProviderId(canonicalProviderId);
        Map<String, RatingSummaryResponse> ratings =
                reviewService.getRatingSummaries(List.of(canonicalProviderId));
        List<ProviderVerification> verifications = hasText(profile.getId())
                ? verificationRepository.findAllByProviderIdIn(List.of(profile.getId()))
                : List.of();
        PublicReviewContent reviewContent = loadReviews(canonicalProviderId, reviewPage);

        RatingSummaryResponse summary = ratings == null ? null : ratings.get(canonicalProviderId);
        boolean verified = verifications != null && verifications.stream()
                .anyMatch(verification -> verification != null
                        && verification.getStatus() == ProviderVerificationStatus.APPROVED);

        ListingResponse bestListing = listings.stream().min(BEST_LISTING).orElse(null);
        List<PublicProviderListingSummary> listingSummaries = new ArrayList<>(listings.size());
        for (ListingResponse listing : listings) {
            listingSummaries.add(new PublicProviderListingSummary(
                    listing.id(),
                    listing.title(),
                    listing.description(),
                    listing.categoryName(),
                    pricing(listing),
                    listingImage(listing),
                    listing.coverageRadiusKm(),
                    listing.serviceAreas()
            ));
        }

        boolean reviewContentAvailable = REVIEW_CONTENT_AVAILABLE.equals(reviewContent.status());
        return new PublicProviderProfileResponse(
                canonicalProviderId,
                profile.getId(),
                profile.getDisplayName(),
                profile.getProfileImageUrl(),
                verified,
                rating(summary),
                reviewCount(summary),
                category(profile, listings),
                serviceTypes(listings),
                profile.getBio(),
                joinLocation(profile.getDistrict(), profile.getCity()),
                bestListing == null ? emptyPricing() : pricing(bestListing),
                bestListing == null ? null : bestListing.coverageRadiusKm(),
                listingSummaries,
                UNAVAILABLE,
                new PublicProfileSupportedFlags(false, false, reviewContentAvailable, false),
                reviewContent.content(),
                reviewContent.status(),
                reviewContent.page(),
                reviewContent.size(),
                reviewContent.totalElements(),
                reviewContent.totalPages()
        );
    }

    private PublicReviewContent loadReviews(String canonicalProviderId, int requestedPage) {
        try {
            ReviewPageResponse page = reviewService.getPublishedReviews(
                    canonicalProviderId, requestedPage, REVIEW_PAGE_SIZE);
            if (page == null) {
                return new PublicReviewContent(
                        List.of(), REVIEW_CONTENT_UNAVAILABLE, Math.max(requestedPage, 0),
                        REVIEW_PAGE_SIZE, 0L, 0);
            }

            List<PublicProviderReviewResponse> mapped = new ArrayList<>();
            if (page.content() != null) {
                for (ReviewResponse review : page.content()) {
                    PublicProviderReviewResponse publicReview = mapReview(review);
                    if (publicReview != null) {
                        mapped.add(publicReview);
                    }
                }
            }
            return new PublicReviewContent(
                    mapped, REVIEW_CONTENT_AVAILABLE, page.page(), page.size(),
                    page.totalElements(), page.totalPages());
        } catch (RuntimeException error) {
            log.warn("event=PUBLIC_PROFILE_REVIEWS_UNAVAILABLE providerId={} cause={}",
                    canonicalProviderId, error.getClass().getSimpleName());
            return new PublicReviewContent(
                    List.of(), REVIEW_CONTENT_UNAVAILABLE, Math.max(requestedPage, 0),
                    REVIEW_PAGE_SIZE, 0L, 0);
        }
    }

    private PublicProviderReviewResponse mapReview(ReviewResponse review) {
        if (review == null) {
            return null;
        }
        var reply = review.providerReply();
        return new PublicProviderReviewResponse(
                review.id(),
                review.rating(),
                review.comment(),
                instantString(review.createdAt()),
                review.edited(),
                instantString(review.editedAt()),
                reply == null
                        ? null
                        : new PublicProviderReviewResponse.PublicProviderReplyResponse(
                                reply.text(), instantString(reply.repliedAt())));
    }

    private static String instantString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private record PublicReviewContent(
            List<PublicProviderReviewResponse> content,
            String status,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }

    private ProviderProfile resolveProfile(String providerIdOrProfileId) {
        if (!hasText(providerIdOrProfileId)) {
            throw profileNotFound();
        }
        String identifier = providerIdOrProfileId.trim();
        Optional<ProviderProfile> byId = providerProfileRepository.findById(identifier);
        if (byId.isPresent()) {
            return byId.get();
        }
        return providerProfileRepository.findByUserId(identifier).orElseThrow(this::profileNotFound);
    }

    private ResourceNotFoundException profileNotFound() {
        return new ResourceNotFoundException(
                "PROVIDER_PROFILE_NOT_FOUND", "Provider profile not found");
    }

    private String category(ProviderProfile profile, List<ListingResponse> listings) {
        for (ListingResponse listing : listings) {
            if (hasText(listing.categoryName())) {
                return listing.categoryName().trim();
            }
        }

        List<String> profileCategories = profile.getCategoryIds();
        if (profileCategories == null || profileCategories.isEmpty() || categoryLookupPortProvider == null) {
            return null;
        }

        CategoryLookupPort categoryLookupPort = categoryLookupPortProvider.getIfAvailable();
        if (categoryLookupPort == null) {
            return null;
        }

        List<CategorySnapshot> activeCategories = categoryLookupPort.findActiveCategories();
        if (activeCategories == null || activeCategories.isEmpty()) {
            return null;
        }

        Map<String, String> namesById = new LinkedHashMap<>();
        for (CategorySnapshot category : activeCategories) {
            if (category != null && category.active() && hasText(category.id()) && hasText(category.name())) {
                namesById.putIfAbsent(category.id().trim(), category.name().trim());
            }
        }

        List<String> names = new ArrayList<>();
        for (String categoryId : profileCategories) {
            String name = categoryId == null ? null : namesById.get(categoryId.trim());
            if (hasText(name) && !names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private List<String> serviceTypes(List<ListingResponse> listings) {
        Set<String> serviceTypes = new LinkedHashSet<>();
        for (ListingResponse listing : listings) {
            if (hasText(listing.categoryName())) {
                serviceTypes.add(listing.categoryName().trim());
            }
            if (hasText(listing.title())) {
                serviceTypes.add(listing.title().trim());
            }
        }
        return new ArrayList<>(serviceTypes);
    }

    private PublicPricingResponse pricing(ListingResponse listing) {
        PricingModel model = listing.pricingModel();
        return PublicPricingResponse.from(listing.basePrice(), model, listing.durationMin());
    }

    private PublicPricingResponse emptyPricing() {
        return new PublicPricingResponse(null, null, null, null, null);
    }

    private String listingImage(ListingResponse listing) {
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
        return null;
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
