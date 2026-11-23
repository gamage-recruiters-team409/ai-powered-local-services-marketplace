package com.gamagerecruiters.marketplace.modules.search.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;
import com.gamagerecruiters.marketplace.modules.search.service.PublicProviderProfileServiceImpl;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

class PublicProviderProfileControllerTest {

    private ProviderProfileRepository profileRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ListingService listingService = mock(ListingService.class);
        profileRepository = mock(ProviderProfileRepository.class);
        ReviewService reviewService = mock(ReviewService.class);
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
                .andExpect(jsonPath("$.supported.reviewContent").value(false))
                .andExpect(jsonPath("$.supported.backgroundCheck").value(false))
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.bookingId").doesNotExist())
                .andExpect(jsonPath("$.moderationReason").doesNotExist())
                .andExpect(jsonPath("$.verificationDocuments").doesNotExist())
                .andExpect(jsonPath("$.reviews").doesNotExist());

        mockMvc.perform(get("/api/v1/providers/user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("user-1"));
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
