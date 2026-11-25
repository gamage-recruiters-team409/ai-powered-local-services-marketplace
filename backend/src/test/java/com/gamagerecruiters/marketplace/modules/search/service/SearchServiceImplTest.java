package com.gamagerecruiters.marketplace.modules.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;
import com.gamagerecruiters.marketplace.modules.search.dto.ProviderSearchResult;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchRequest;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchResponse;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

class SearchServiceImplTest {

    private ListingService listingService;
    private ProviderProfileRepository providerProfileRepository;
    private ReviewService reviewService;
    private ProviderVerificationRepository verificationRepository;
    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        listingService = mock(ListingService.class);
        providerProfileRepository = mock(ProviderProfileRepository.class);
        reviewService = mock(ReviewService.class);
        verificationRepository = mock(ProviderVerificationRepository.class);
        service = new SearchServiceImpl(
                listingService, providerProfileRepository, reviewService, verificationRepository);
    }

    @Test
    @DisplayName("Rejects every legacy filter and sort=rating with UNSUPPORTED_SEARCH_FILTER")
    void rejectsUnsupportedFilters() {
        TestRequest request = request("recommended", 0, 12).withPresentParams(Set.of(
                "serviceTypes", "availability", "maxDistanceMiles", "backgroundChecked", "minRating"))
                .withSort("rating");

        assertThatThrownBy(() -> service.search(request.toSearchRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getErrorCode()).isEqualTo("UNSUPPORTED_SEARCH_FILTER");
                    assertThat(apiEx.getMessage()).contains(
                            "serviceTypes", "availability", "maxDistanceMiles",
                            "backgroundChecked", "minRating", "sort=rating");
                });

        verifyNoInteractions(listingService, providerProfileRepository, reviewService, verificationRepository);
    }

    @Test
    @DisplayName("Rejects unknown query params with UNSUPPORTED_SEARCH_FILTER")
    void rejectsUnknownParams() {
        TestRequest request = request("recommended", 0, 12).withPresentParams(Set.of("distance"));

        assertThatThrownBy(() -> service.search(request.toSearchRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getErrorCode()).isEqualTo("UNSUPPORTED_SEARCH_FILTER");
                    assertThat(apiEx.getMessage()).contains("distance");
                });

        verifyNoInteractions(listingService, providerProfileRepository, reviewService, verificationRepository);
    }

    @Test
    @DisplayName("Rejects invalid page, size, sort and length bounds before querying listings")
    void rejectsInvalidParameters() {
        assertBadRequest(request("recommended", -1, 12));
        assertBadRequest(request("recommended", 0, 0));
        assertBadRequest(request("recommended", 0, 25));
        assertBadRequest(request("bogus", 0, 12));
        assertBadRequest(request("recommended", 0, 12).withQ("x".repeat(121)));
        assertBadRequest(request("recommended", 0, 12).withLocation("y".repeat(121)));
        assertBadRequest(request("recommended", 0, 12).withCategory("z".repeat(101)));
        assertBadRequest(request("recommended", 0, 12).withMaxPrice(new BigDecimal("-1")));

        verifyNoInteractions(listingService, providerProfileRepository, reviewService, verificationRepository);
    }

    @Test
    @DisplayName("Supported filters, sort and pagination are delegated; results map from batch lookups")
    void mapsSearchPageWithBatchLookups() {
        ListingResponse first = listing("listing-1", "user-1", new BigDecimal("3000.00"),
                "Wiring Repair", "Licensed Home Electrical Repair", "https://cdn.example.com/1.jpg", 15);
        ListingResponse second = listing("listing-2", "user-2", null,
                "Painting", "Interior Painting", null, null);
        Pageable requestedPageable = PageRequest.of(1, 2,
                Sort.by(Sort.Order.asc("basePrice"), Sort.Order.asc("id")));
        Page<ListingResponse> listingPage = new PageImpl<>(List.of(first, second), requestedPageable, 7L);
        when(listingService.searchPublicListings(any(), any(), any(), any(), any(), any()))
                .thenReturn(listingPage);

        ProviderProfile profileOne = profile("profile-1", "user-1", "Ada Electrical", "https://cdn.example.com/avatar.jpg");
        ProviderProfile profileTwo = profile("profile-2", "user-2", "Ben Painting",
                "https://cdn.example.com/avatar2.jpg");
        when(providerProfileRepository.findAllByUserIdIn(any()))
                .thenReturn(List.of(profileOne, profileTwo));
        when(verificationRepository.findAllByProviderIdIn(any()))
                .thenReturn(List.of(verification("profile-1", ProviderVerificationStatus.APPROVED),
                        verification("profile-2", ProviderVerificationStatus.UNDER_REVIEW)));
        when(reviewService.getRatingSummaries(any()))
                .thenReturn(Map.of(
                        "user-1", summary("user-1", 4.5, 2),
                        "user-2", summary("user-2", 0.0, 0)));

        TestRequest request = request("price_asc", 1, 2)
                .withQ("wiring")
                .withLocation("Colombo")
                .withCategory("cat-1")
                .withMaxPrice(new BigDecimal("5000"));
        SearchResponse response = service.search(request.toSearchRequest());

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalCount()).isEqualTo(7L);
        assertThat(response.unsupportedFilters()).isEmpty();
        assertThat(response.results()).hasSize(2);

        ProviderSearchResult resultOne = response.results().get(0);
        assertThat(resultOne.providerId()).isEqualTo("user-1");
        assertThat(resultOne.profileId()).isEqualTo("profile-1");
        assertThat(resultOne.listingId()).isEqualTo("listing-1");
        assertThat(resultOne.name()).isEqualTo("Ada Electrical");
        assertThat(resultOne.image()).isEqualTo("https://cdn.example.com/1.jpg");
        assertThat(resultOne.verified()).isTrue();
        assertThat(resultOne.rating()).isEqualTo(4.5);
        assertThat(resultOne.reviewCount()).isEqualTo(2L);
        assertThat(resultOne.category()).isEqualTo("Wiring Repair");
        assertThat(resultOne.location()).isEqualTo("Colombo, Dehiwala");
        assertThat(resultOne.pricing().amount()).isEqualByComparingTo("3000.00");
        assertThat(resultOne.pricing().model()).isEqualTo("FIXED");
        assertThat(resultOne.pricing().durationMinutes()).isEqualTo(60);
        assertThat(resultOne.pricing().currency()).isNull();
        assertThat(resultOne.pricing().unit()).isNull();
        assertThat(resultOne.coverageRadiusKm()).isEqualTo(15);
        assertThat(resultOne.supported().distance()).isFalse();
        assertThat(resultOne.supported().availability()).isFalse();
        assertThat(resultOne.supported().backgroundCheck()).isFalse();
        assertThat(resultOne.supported().serviceTypes()).isFalse();

        ProviderSearchResult resultTwo = response.results().get(1);
        assertThat(resultTwo.profileId()).isEqualTo("profile-2");
        assertThat(resultTwo.name()).isEqualTo("Ben Painting");
        assertThat(resultTwo.image()).isEqualTo("https://cdn.example.com/avatar2.jpg");
        assertThat(resultTwo.verified()).isFalse();
        assertThat(resultTwo.rating()).isNull();
        assertThat(resultTwo.reviewCount()).isZero();
        assertThat(resultTwo.pricing().amount()).isNull();
        assertThat(resultTwo.pricing().model()).isNull();
        assertThat(resultTwo.pricing().durationMinutes()).isNull();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(listingService).searchPublicListings(
                eq("wiring"), eq("Colombo"), eq("cat-1"), eq(new BigDecimal("5000")),
                isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("basePrice").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.ASC);

        verify(providerProfileRepository).findAllByUserIdIn(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> verificationIdsCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(verificationRepository).findAllByProviderIdIn(verificationIdsCaptor.capture());
        assertThat(verificationIdsCaptor.getValue()).containsExactly("profile-1", "profile-2");
        verify(reviewService).getRatingSummaries(any());
        verify(providerProfileRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("verified=true resolves approved profile ids to user ids and maps verification status back")
    void verifiedFilterResolvesProfileIdsToUserIds() {
        ListingResponse listing = listing("listing-1", "user-1", new BigDecimal("100"),
                "Cleaning", "Home Cleaning", null, 10);
        ProviderProfile profile = profile("profile-1", "user-1", "Ada Cleaning", null);
        when(verificationRepository.findProviderIdsByStatus(ProviderVerificationStatus.APPROVED))
                .thenReturn(List.of(() -> "profile-1"));
        when(providerProfileRepository.findAllById(any()))
                .thenReturn(List.of(profile));
        when(listingService.searchPublicListings(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing), PageRequest.of(0, 12), 1L));
        when(providerProfileRepository.findAllByUserIdIn(Set.of("user-1")))
                .thenReturn(List.of(profile));
        when(verificationRepository.findAllByProviderIdIn(List.of("profile-1")))
                .thenReturn(List.of(verification("profile-1", ProviderVerificationStatus.APPROVED)));
        when(reviewService.getRatingSummaries(any())).thenReturn(Map.of());

        SearchResponse response = service.search(request("recommended", 0, 12).withVerified(true).toSearchRequest());

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).providerId()).isEqualTo("user-1");
        assertThat(response.results().get(0).profileId()).isEqualTo("profile-1");
        assertThat(response.results().get(0).verified()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> idsCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(listingService).searchPublicListings(any(), any(), any(), any(), idsCaptor.capture(), any());
        assertThat(idsCaptor.getValue()).containsExactly("user-1");
        verify(verificationRepository).findAllByProviderIdIn(List.of("profile-1"));
    }

    @Test
    @DisplayName("verified=true with no approved providers returns empty response without calling listing search")
    void verifiedFilterWithNoApprovedProvidersReturnsEmpty() {
        when(verificationRepository.findProviderIdsByStatus(ProviderVerificationStatus.APPROVED))
                .thenReturn(List.of());

        SearchResponse response = service.search(request("recommended", 0, 12).withVerified(true).toSearchRequest());

        assertThat(response.results()).isEmpty();
        assertThat(response.totalCount()).isZero();
        verify(listingService, never()).searchPublicListings(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Missing image, rating and price stay null instead of being fabricated")
    void keepsMissingValuesNull() {
        ListingResponse listing = listing("listing-3", "user-3", null, null, "Untitled", null, null);
        when(listingService.searchPublicListings(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing), PageRequest.of(0, 12), 1L));
        when(reviewService.getRatingSummaries(any()))
                .thenReturn(Map.of("user-3", summary("user-3", 0.0, 0)));

        SearchResponse response = service.search(request("recommended", 0, 12).toSearchRequest());

        ProviderSearchResult result = response.results().get(0);
        assertThat(result.profileId()).isNull();
        assertThat(result.image()).isNull();
        assertThat(result.rating()).isNull();
        assertThat(result.reviewCount()).isZero();
        assertThat(result.category()).isNull();
        assertThat(result.pricing().amount()).isNull();
        assertThat(result.pricing().model()).isNull();
        assertThat(result.pricing().durationMinutes()).isNull();
        assertThat(result.pricing().currency()).isNull();
        assertThat(result.pricing().unit()).isNull();
        assertThat(result.coverageRadiusKm()).isNull();
    }

    private void assertBadRequest(TestRequest request) {
        assertThatThrownBy(() -> service.search(request.toSearchRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static TestRequest request(String sort, int page, int size) {
        return new TestRequest(null, null, null, null, false, sort, page, size, Set.of());
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
                "Description for " + id,
                price == null ? null : PricingModel.FIXED,
                price,
                price == null ? null : 60,
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

    private static ProviderProfile profile(String id, String userId, String name, String image) {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(id);
        profile.setUserId(userId);
        profile.setDisplayName(name);
        profile.setProfileImageUrl(image);
        profile.setBio("Public bio for " + name);
        profile.setDistrict("Colombo");
        profile.setCity("Dehiwala");
        profile.setCategoryIds(List.of("cat-1"));
        return profile;
    }

    private static ProviderVerification verification(String providerId, ProviderVerificationStatus status) {
        ProviderVerification verification = new ProviderVerification();
        verification.setProviderId(providerId);
        verification.setStatus(status);
        return verification;
    }

    private static RatingSummaryResponse summary(String providerId, double average, long count) {
        return new RatingSummaryResponse(providerId, average, count, Map.of());
    }

    private static final class TestRequest {
        private String q;
        private String location;
        private String category;
        private BigDecimal maxPrice;
        private boolean verified;
        private String sort;
        private final int page;
        private final int size;
        private Set<String> presentParams;

        private TestRequest(
                String q,
                String location,
                String category,
                BigDecimal maxPrice,
                boolean verified,
                String sort,
                int page,
                int size,
                Set<String> presentParams
        ) {
            this.q = q;
            this.location = location;
            this.category = category;
            this.maxPrice = maxPrice;
            this.verified = verified;
            this.sort = sort;
            this.page = page;
            this.size = size;
            this.presentParams = presentParams;
        }

        private TestRequest withQ(String value) {
            q = value;
            return this;
        }

        private TestRequest withLocation(String value) {
            location = value;
            return this;
        }

        private TestRequest withCategory(String value) {
            category = value;
            return this;
        }

        private TestRequest withMaxPrice(BigDecimal value) {
            maxPrice = value;
            return this;
        }

        private TestRequest withVerified(boolean value) {
            verified = value;
            return this;
        }

        private TestRequest withSort(String value) {
            sort = value;
            return this;
        }

        private TestRequest withPresentParams(Set<String> value) {
            presentParams = value;
            return this;
        }

        private SearchRequest toSearchRequest() {
            return new SearchRequest(q, location, category, maxPrice, verified, sort, page, size, presentParams);
        }
    }
}
