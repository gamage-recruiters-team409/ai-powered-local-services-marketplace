package com.gamagerecruiters.marketplace.modules.search.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;
import com.gamagerecruiters.marketplace.modules.search.dto.SearchRequest;
import com.gamagerecruiters.marketplace.modules.search.service.SearchServiceImpl;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;

class SearchControllerTest {

    private SearchServiceImpl searchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ListingService listingService = mock(ListingService.class);
        ProviderProfileRepository profileRepository = mock(ProviderProfileRepository.class);
        ReviewService reviewService = mock(ReviewService.class);
        ProviderVerificationRepository verificationRepository = mock(ProviderVerificationRepository.class);

        when(listingService.searchPublicListings(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(5);
                    return new PageImpl<>(List.of(), pageable, 0L);
                });
        when(reviewService.getRatingSummaries(any())).thenReturn(Map.of());

        searchService = spy(new SearchServiceImpl(
                listingService, profileRepository, reviewService, verificationRepository));
        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(searchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/search returns the documented envelope and default parameters")
    void returnsSearchEnvelopeWithDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(12))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.unsupportedFilters").isArray())
                .andExpect(jsonPath("$.unsupportedFilters").isEmpty());

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService).search(captor.capture());
        SearchRequest captured = captor.getValue();
        assertThat(captured.sort()).isEqualTo("recommended");
        assertThat(captured.page()).isZero();
        assertThat(captured.size()).isEqualTo(12);
        assertThat(captured.verified()).isFalse();
    }

    @Test
    @DisplayName("Supported query params are passed through as an explicit server-side request")
    void passesSupportedParams() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "wiring")
                        .param("location", "Colombo")
                        .param("category", "cat-1")
                        .param("maxPrice", "5000.00")
                        .param("verified", "true")
                        .param("sort", "price_asc")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService).search(captor.capture());
        SearchRequest captured = captor.getValue();
        assertThat(captured.q()).isEqualTo("wiring");
        assertThat(captured.location()).isEqualTo("Colombo");
        assertThat(captured.category()).isEqualTo("cat-1");
        assertThat(captured.maxPrice()).isEqualByComparingTo("5000.00");
        assertThat(captured.verified()).isTrue();
        assertThat(captured.sort()).isEqualTo("price_asc");
        assertThat(captured.presentParams()).contains(
                "q", "location", "category", "maxPrice", "verified", "sort", "page", "size");
    }

    @Test
    @DisplayName("Legacy filters and sort=rating are rejected with 400 UNSUPPORTED_SEARCH_FILTER")
    void rejectsUnsupportedFilters() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("serviceTypes", "standard"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_SEARCH_FILTER"));

        mockMvc.perform(get("/api/v1/search").param("sort", "rating"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_SEARCH_FILTER"));
    }

    @Test
    @DisplayName("Out-of-range page/size and malformed maxPrice are rejected with 400")
    void rejectsInvalidPaginationAndPrice() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search").param("size", "25"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search").param("maxPrice", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
