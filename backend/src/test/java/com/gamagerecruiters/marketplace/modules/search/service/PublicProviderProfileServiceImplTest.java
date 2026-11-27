package com.gamagerecruiters.marketplace.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort.CategorySnapshot;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;
import com.gamagerecruiters.marketplace.modules.search.dto.PublicProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

class PublicProviderProfileServiceImplTest {

    private ListingService listingService;
    private ProviderProfileRepository profileRepository;
    private ReviewService reviewService;
    private ProviderVerificationRepository verificationRepository;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CategoryLookupPort> categoryLookupPortProvider = mock(ObjectProvider.class);
    private final CategoryLookupPort categoryLookupPort = mock(CategoryLookupPort.class);
    private PublicProviderProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        listingService = mock(ListingService.class);
        profileRepository = mock(ProviderProfileRepository.class);
        reviewService = mock(ReviewService.class);
        verificationRepository = mock(ProviderVerificationRepository.class);
        service = new PublicProviderProfileServiceImpl(
                listingService, profileRepository, reviewService, verificationRepository,
                categoryLookupPortProvider);
    }

    @Test
    @DisplayName("Resolves by Mongo profile id and aggregates only public listing data")
    void resolvesByProfileId() {
        ProviderProfile profile = profile("profile-1", "user-1", "Ada Electrical",
                "https://cdn.example.com/avatar.jpg", List.of("cat-a", "cat-b"));
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        ListingResponse wiring = listing("listing-1", "user-1", new BigDecimal("3000.00"),
                "Wiring Repair", "Licensed Home Electrical Repair", "https://cdn.example.com/wiring.jpg", 15);
        ListingResponse painting = listing("listing-2", "user-1", new BigDecimal("1500.00"),
                "Painting", "Interior Painting", null, 25);
        when(listingService.getPublicListingsByProviderId("user-1"))
                .thenReturn(List.of(wiring, painting));
        when(reviewService.getRatingSummaries(List.of("user-1")))
                .thenReturn(Map.of("user-1", summary("user-1", 4.8, 4)));
        when(reviewService.getPublishedReviews("user-1", 0, 5)).thenReturn(new ReviewPageResponse(
                List.of(review("review-1", "user-1", "Reliable wiring", null)), 0, 5, 1, 1));
        when(verificationRepository.findAllByProviderIdIn(List.of("profile-1")))
                .thenReturn(List.of(verification("profile-1", ProviderVerificationStatus.APPROVED)));

        PublicProviderProfileResponse response = service.getPublicProfile("profile-1");

        assertThat(response.providerId()).isEqualTo("user-1");
        assertThat(response.profileId()).isEqualTo("profile-1");
        assertThat(response.name()).isEqualTo("Ada Electrical");
        assertThat(response.image()).isEqualTo("https://cdn.example.com/avatar.jpg");
        assertThat(response.verified()).isTrue();
        assertThat(response.rating()).isEqualTo(4.8);
        assertThat(response.reviewCount()).isEqualTo(4L);
        assertThat(response.category()).isEqualTo("Wiring Repair");
        assertThat(response.serviceTypes()).containsExactly(
                "Wiring Repair", "Licensed Home Electrical Repair", "Painting", "Interior Painting");
        assertThat(response.description()).isEqualTo("Public bio for Ada Electrical");
        assertThat(response.location()).isEqualTo("Colombo, Dehiwala");
        assertThat(response.pricing().amount()).isEqualByComparingTo("1500.00");
        assertThat(response.pricing().model()).isEqualTo("FIXED");
        assertThat(response.pricing().durationMinutes()).isEqualTo(60);
        assertThat(response.pricing().currency()).isNull();
        assertThat(response.pricing().unit()).isNull();
        assertThat(response.coverageRadiusKm()).isEqualTo(25);
        assertThat(response.listings()).extracting("id").containsExactly("listing-1", "listing-2");
        assertThat(response.listings().get(0).pricing().amount()).isEqualByComparingTo("3000.00");
        assertThat(response.listings().get(1).image()).isNull();
        assertThat(response.listings().get(1).serviceAreas()).containsExactly("Dehiwala");
        assertThat(response.availabilityStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.supported().distance()).isFalse();
        assertThat(response.supported().availability()).isFalse();
        assertThat(response.supported().reviewContent()).isTrue();
        assertThat(response.supported().backgroundCheck()).isFalse();
        assertThat(response.reviewContentStatus()).isEqualTo("AVAILABLE");
        assertThat(response.reviews()).hasSize(1);
        assertThat(response.reviews().get(0).id()).isEqualTo("review-1");
        assertThat(response.reviews().get(0).rating()).isEqualTo(5);
        assertThat(response.reviews().get(0).comment()).isEqualTo("Reliable wiring");
        assertThat(response.reviews().get(0).createdAt())
                .isEqualTo("2026-11-20T10:15:30Z");
        assertThat(response.reviewPage()).isZero();
        assertThat(response.reviewPageSize()).isEqualTo(5);
        assertThat(response.reviewTotalElements()).isEqualTo(1L);
        assertThat(response.reviewTotalPages()).isEqualTo(1);

        verify(profileRepository, never()).findByUserId(any());
        verify(listingService).getPublicListingsByProviderId("user-1");
        verify(reviewService).getRatingSummaries(List.of("user-1"));
        verify(reviewService).getPublishedReviews("user-1", 0, 5);
        verify(verificationRepository).findAllByProviderIdIn(List.of("profile-1"));
    }

    @Test
    @DisplayName("Uses the provider profile document id for verification when profile and user ids differ")
    void usesProfileIdForVerificationLookup() {
        ProviderProfile profile = profile("profile-verified-1", "user-verified-1", "Verified Provider", null,
                List.of("cat-a"));
        when(profileRepository.findByUserId("user-verified-1")).thenReturn(Optional.of(profile));
        when(listingService.getPublicListingsByProviderId("user-verified-1")).thenReturn(List.of());
        when(reviewService.getRatingSummaries(List.of("user-verified-1"))).thenReturn(Map.of());
        when(verificationRepository.findAllByProviderIdIn(List.of("profile-verified-1")))
                .thenReturn(List.of(verification("profile-verified-1", ProviderVerificationStatus.APPROVED)));

        PublicProviderProfileResponse response = service.getPublicProfile("user-verified-1");

        assertThat(response.providerId()).isEqualTo("user-verified-1");
        assertThat(response.profileId()).isEqualTo("profile-verified-1");
        assertThat(response.verified()).isTrue();
        verify(verificationRepository).findAllByProviderIdIn(List.of("profile-verified-1"));
        verify(verificationRepository, never()).findAllByProviderIdIn(List.of("user-verified-1"));
    }

    @Test
    @DisplayName("Resolves by canonical userId and falls back to readable active category names when no listings exist")
    void resolvesByUserId() {
        ProviderProfile profile = profile("profile-2", "user-2", "Ben Painting", null,
                List.of("cat-a", "cat-b", "cat-missing"));
        when(profileRepository.findById("user-2")).thenReturn(Optional.empty());
        when(profileRepository.findByUserId("user-2")).thenReturn(Optional.of(profile));
        when(listingService.getPublicListingsByProviderId("user-2")).thenReturn(List.of());
        when(reviewService.getRatingSummaries(List.of("user-2")))
                .thenReturn(Map.of("user-2", summary("user-2", 0.0, 0)));
        when(verificationRepository.findAllByProviderIdIn(List.of("profile-2")))
                .thenReturn(List.of(verification("profile-2", ProviderVerificationStatus.SUBMITTED)));
        when(categoryLookupPortProvider.getIfAvailable()).thenReturn(categoryLookupPort);
        when(categoryLookupPort.findActiveCategories()).thenReturn(List.of(
                new CategorySnapshot("cat-a", "Electrical", null, true, new BigDecimal("0.10")),
                new CategorySnapshot("cat-b", "Home Cleaning", null, true, new BigDecimal("0.12")),
                new CategorySnapshot("cat-missing", "Hidden Category", null, false, new BigDecimal("0.10"))));

        PublicProviderProfileResponse response = service.getPublicProfile("user-2");

        assertThat(response.providerId()).isEqualTo("user-2");
        assertThat(response.profileId()).isEqualTo("profile-2");
        assertThat(response.verified()).isFalse();
        assertThat(response.rating()).isNull();
        assertThat(response.reviewCount()).isZero();
        assertThat(response.category()).isEqualTo("Electrical, Home Cleaning");
        assertThat(response.serviceTypes()).isEmpty();
        assertThat(response.pricing().amount()).isNull();
        assertThat(response.pricing().model()).isNull();
        assertThat(response.pricing().durationMinutes()).isNull();
        assertThat(response.coverageRadiusKm()).isNull();
        assertThat(response.listings()).isEmpty();

        verify(profileRepository).findByUserId("user-2");
    }

    @Test
    @DisplayName("Keeps the usable public profile when review content lookup fails")
    void keepsProfileWhenReviewLookupFails() {
        ProviderProfile profile = profile("profile-1", "user-1", "Ada Electrical", null, List.of("cat-a"));
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(listingService.getPublicListingsByProviderId("user-1")).thenReturn(List.of());
        when(reviewService.getRatingSummaries(List.of("user-1")))
                .thenReturn(Map.of("user-1", summary("user-1", 4.7, 3)));
        when(reviewService.getPublishedReviews("user-1", 2, 5))
                .thenThrow(new RuntimeException("review store unavailable"));

        PublicProviderProfileResponse response = service.getPublicProfile("profile-1", 2);

        assertThat(response.rating()).isEqualTo(4.7);
        assertThat(response.reviewCount()).isEqualTo(3L);
        assertThat(response.reviewContentStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.supported().reviewContent()).isFalse();
        assertThat(response.reviews()).isEmpty();
        assertThat(response.reviewPage()).isEqualTo(2);
        assertThat(response.reviewTotalElements()).isZero();
        assertThat(response.reviewTotalPages()).isZero();
        verify(reviewService).getPublishedReviews("user-1", 2, 5);
    }

    @Test
    @DisplayName("Maps only the safe public review subset and honors the requested review page")
    void mapsSafeReviewSubsetForRequestedPage() {
        ProviderProfile profile = profile("profile-1", "user-1", "Ada Electrical", null, List.of("cat-a"));
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(listingService.getPublicListingsByProviderId("user-1")).thenReturn(List.of());
        when(reviewService.getRatingSummaries(List.of("user-1")))
                .thenReturn(Map.of("user-1", summary("user-1", 4.4, 12)));
        when(reviewService.getPublishedReviews("user-1", 2, 5)).thenReturn(new ReviewPageResponse(
                List.of(review(
                        "review-11",
                        "user-1",
                        "Great service",
                        new ProviderReplyResponse("Thank you", Instant.parse("2026-11-21T09:00:00Z")))),
                2, 5, 12, 3));

        PublicProviderProfileResponse response = service.getPublicProfile("profile-1", 2);

        assertThat(response.reviewContentStatus()).isEqualTo("AVAILABLE");
        assertThat(response.reviewPage()).isEqualTo(2);
        assertThat(response.reviewPageSize()).isEqualTo(5);
        assertThat(response.reviewTotalElements()).isEqualTo(12L);
        assertThat(response.reviewTotalPages()).isEqualTo(3);
        assertThat(response.reviews()).hasSize(1);
        assertThat(response.reviews().get(0).id()).isEqualTo("review-11");
        assertThat(response.reviews().get(0).providerReply().text()).isEqualTo("Thank you");
        assertThat(response.reviews().get(0).providerReply().repliedAt())
                .isEqualTo("2026-11-21T09:00:00Z");
        verify(reviewService).getPublishedReviews("user-1", 2, 5);
    }

    @Test
    @DisplayName("Returns 404 PROVIDER_PROFILE_NOT_FOUND for an unknown id or userId")
    void returnsNotFound() {
        when(profileRepository.findById("missing")).thenReturn(Optional.empty());
        when(profileRepository.findByUserId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPublicProfile("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(ex -> {
                    ResourceNotFoundException notFound = (ResourceNotFoundException) ex;
                    assertThat(notFound.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(notFound.getErrorCode()).isEqualTo("PROVIDER_PROFILE_NOT_FOUND");
                });

        verifyNoListingLookups();
    }

    private void verifyNoListingLookups() {
        verify(listingService, never()).getPublicListingsByProviderId(any());
        verify(reviewService, never()).getRatingSummaries(any());
        verify(reviewService, never()).getPublishedReviews(any(), anyInt(), anyInt());
        verify(verificationRepository, never()).findAllByProviderIdIn(any());
    }

    private static ProviderProfile profile(
            String id,
            String userId,
            String name,
            String image,
            List<String> categoryIds
    ) {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(id);
        profile.setUserId(userId);
        profile.setDisplayName(name);
        profile.setProfileImageUrl(image);
        profile.setBio("Public bio for " + name);
        profile.setDistrict("Colombo");
        profile.setCity("Dehiwala");
        profile.setCategoryIds(categoryIds);
        return profile;
    }

    private static ListingResponse listing(
            String id,
            String providerId,
            BigDecimal price,
            String categoryName,
            String title,
            String image,
            Integer coverageRadiusKm
    ) {
        return new ListingResponse(
                id,
                providerId,
                "cat-1",
                categoryName,
                null,
                title,
                "Public description for " + id,
                PricingModel.FIXED,
                price,
                60,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                null,
                coverageRadiusKm,
                List.of("Dehiwala"),
                image == null ? List.of() : List.of(image),
                null,
                List.of(),
                ListingStatus.ACTIVE,
                true,
                List.of(),
                null,
                null,
                0.0,
                0,
                null,
                null,
                null
        );
    }

    private static ProviderVerification verification(String providerId, ProviderVerificationStatus status) {
        ProviderVerification verification = new ProviderVerification();
        verification.setProviderId(providerId);
        verification.setStatus(status);
        return verification;
    }

    private static ReviewResponse review(
            String id,
            String providerId,
            String comment,
            ProviderReplyResponse providerReply
    ) {
        return new ReviewResponse(
                id,
                "booking-" + id,
                "listing-" + id,
                providerId,
                5,
                comment,
                ReviewStatus.PUBLISHED,
                providerReply,
                false,
                null,
                Instant.parse("2026-11-20T10:15:30Z"),
                "Private Customer Name",
                "private-removal-reason");
    }

    private static RatingSummaryResponse summary(String providerId, double average, long count) {
        return new RatingSummaryResponse(providerId, average, count, Map.of());
    }
}
