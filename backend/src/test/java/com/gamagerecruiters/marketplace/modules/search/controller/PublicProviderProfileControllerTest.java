package com.gamagerecruiters.marketplace.modules.search.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.modules.listing.service.CategoryLookupPort;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;
import com.gamagerecruiters.marketplace.modules.search.service.PublicProviderProfileServiceImpl;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

class PublicProviderProfileControllerTest {

    private ProviderProfileRepository profileRepository;
    private ReviewService reviewService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ListingService listingService = mock(ListingService.class);
        profileRepository = mock(ProviderProfileRepository.class);
        reviewService = mock(ReviewService.class);
        ProviderVerificationRepository verificationRepository = mock(ProviderVerificationRepository.class);

        ProviderProfile profile = new ProviderProfile();
        profile.setId("profile-1");
        profile.setUserId("user-1");
        profile.setDisplayName("Ada Electrical");
        profile.setBio("Public bio");
        profile.setDistrict("Colombo");
        profile.setCity("Dehiwala");
        when(profileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(profileRepository.findById("user-1")).thenReturn(Optional.empty());
        when(profileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(profileRepository.findByUserId("missing")).thenReturn(Optional.empty());
        when(listingService.getPublicListingsByProviderId("user-1")).thenReturn(List.of());
        when(reviewService.getRatingSummaries(List.of("user-1")))
                .thenReturn(Map.of("user-1", new RatingSummaryResponse("user-1", 0.0, 0, Map.of())));
        when(reviewService.getPublishedReviews("user-1", 0, 5)).thenReturn(new ReviewPageResponse(
                List.of(new ReviewResponse(
                        "review-1",
                        "booking-private",
                        "listing-private",
                        "user-1",
                        5,
                        "Great service",
                        ReviewStatus.PUBLISHED,
                        new ProviderReplyResponse("Thank you", Instant.parse("2026-11-21T09:00:00Z")),
                        false,
                        null,
                        Instant.parse("2026-11-20T10:15:30Z"),
                        "Private Customer",
                        "private-removal-reason")),
                0, 5, 1, 1));
        when(verificationRepository.findAllByProviderIdIn(List.of("profile-1"))).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<CategoryLookupPort> categoryLookupPortProvider = mock(ObjectProvider.class);
        PublicProviderProfileServiceImpl service = new PublicProviderProfileServiceImpl(
                listingService, profileRepository, reviewService, verificationRepository,
                categoryLookupPortProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicProviderProfileController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/providers/{providerId} exposes only the public aggregate contract")
    void returnsPublicProfile() throws Exception {
        mockMvc.perform(get("/api/v1/providers/profile-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("user-1"))
                .andExpect(jsonPath("$.profileId").value("profile-1"))
                .andExpect(jsonPath("$.availabilityStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.supported.distance").value(false))
                .andExpect(jsonPath("$.supported.availability").value(false))
                .andExpect(jsonPath("$.supported.reviewContent").value(true))
                .andExpect(jsonPath("$.supported.backgroundCheck").value(false))
                .andExpect(jsonPath("$.reviewContentStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.reviewPage").value(0))
                .andExpect(jsonPath("$.reviewPageSize").value(5))
                .andExpect(jsonPath("$.reviewTotalElements").value(1))
                .andExpect(jsonPath("$.reviewTotalPages").value(1))
                .andExpect(jsonPath("$.reviews[0].id").value("review-1"))
                .andExpect(jsonPath("$.reviews[0].rating").value(5))
                .andExpect(jsonPath("$.reviews[0].comment").value("Great service"))
                .andExpect(jsonPath("$.reviews[0].createdAt").value("2026-11-20T10:15:30Z"))
                .andExpect(jsonPath("$.reviews[0].providerReply.text").value("Thank you"))
                .andExpect(jsonPath("$.reviews[0].bookingId").doesNotExist())
                .andExpect(jsonPath("$.reviews[0].listingId").doesNotExist())
                .andExpect(jsonPath("$.reviews[0].providerId").doesNotExist())
                .andExpect(jsonPath("$.reviews[0].customerName").doesNotExist())
                .andExpect(jsonPath("$.reviews[0].removalReason").doesNotExist())
                .andExpect(jsonPath("$.reviews[0].status").doesNotExist())
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.bookingId").doesNotExist())
                .andExpect(jsonPath("$.moderationReason").doesNotExist())
                .andExpect(jsonPath("$.verificationDocuments").doesNotExist());

        mockMvc.perform(get("/api/v1/providers/user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("user-1"));
    }

    @Test
    @DisplayName("GET /api/v1/providers/{providerId} accepts a review page without changing the public path")
    void acceptsReviewPageParam() throws Exception {
        mockMvc.perform(get("/api/v1/providers/user-1").param("reviewPage", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewPage").value(2));

        verify(reviewService).getPublishedReviews("user-1", 2, 5);
    }

    @Test
    @DisplayName("Unknown provider id returns 404 PROVIDER_PROFILE_NOT_FOUND")
    void returnsNotFound() throws Exception {
        when(profileRepository.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/providers/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PROVIDER_PROFILE_NOT_FOUND"));
    }
}
