package com.gamagerecruiters.marketplace.modules.listing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitExceededException;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.DistrictGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.GeoPointDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProviderListingDashboardSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ProvinceGeoDto;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

@WebMvcTest(ListingController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class ListingControllerTest {

    private static final String BASE = "/api/v1/listings";
    private static final String PROVIDER_TOKEN = "valid-provider-token";
    private static final String CUSTOMER_TOKEN = "valid-customer-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private ListingService listingService;

    private void mockUser(String token, String userId, Role role) {
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user);
    }

    private ListingResponse sampleResponse(String id, ListingStatus status) {
        return new ListingResponse(
                id,
                "provider-1",
                "cat-sub",
                "Wiring Repair",
                "cat-root",
                "Licensed Home Electrical Repair",
                "Complete home breaker panel fault diagnosis, earthing test, and certified wiring maintenance.",
                PricingModel.FIXED,
                new BigDecimal("3500.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/wiring-1.jpg"),
                "https://cdn.example.com/wiring-1.jpg",
                List.of(),
                status,
                status == ListingStatus.ACTIVE,
                List.of(),
                null,
                null,
                0.0,
                0,
                1L,
                Instant.now(),
                Instant.now()
        );
    }

    private String validPayloadJson() {
        return """
                {
                  "categoryId": "cat-sub",
                  "title": "Licensed Home Electrical Repair",
                  "description": "Complete home breaker panel fault diagnosis, earthing test, and certified wiring maintenance.",
                  "pricingModel": "FIXED",
                  "basePrice": 3500.00,
                  "durationMin": 90,
                  "executionModel": "ON_SITE",
                  "district": "Colombo",
                  "baseCity": "Dehiwala",
                  "location": { "longitude": 79.8612, "latitude": 6.9271 },
                  "coverageRadiusKm": 15,
                  "serviceAreas": ["Dehiwala"],
                  "images": ["https://cdn.example.com/wiring-1.jpg"],
                  "saveAsDraft": false
                }
                """;
    }

    @Test
    @DisplayName("Unauthenticated requests to provider listing endpoints return 401 UNAUTHORIZED")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get(BASE + "/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        mockMvc.perform(get(BASE + "/me/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put(BASE + "/listing-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch(BASE + "/listing-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete(BASE + "/listing-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CUSTOMER role cannot access provider CRUD endpoints and receives 403 ACCESS_DENIED")
    void customerForbiddenOnProviderMutationsAndMe() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);

        mockMvc.perform(get(BASE + "/me")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(put(BASE + "/listing-1")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(patch(BASE + "/listing-1/status")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("PROVIDER POST /api/v1/listings returns 201 Created with Location header and ACTIVE listing")
    void createListingReturns201() throws Exception {
        mockUser(PROVIDER_TOKEN, "provider-1", Role.PROVIDER);
        when(listingService.createListing(eq("provider-1"), any()))
                .thenReturn(sampleResponse("listing-1", ListingStatus.ACTIVE));

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/listings/listing-1"))
                .andExpect(jsonPath("$.id").value("listing-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Invalid empty payload returns 400 VALIDATION_FAILED with fieldErrors")
    void validationFailureReturns400WithFieldErrors() throws Exception {
        mockUser(PROVIDER_TOKEN, "provider-1", Role.PROVIDER);

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    @DisplayName("PROVIDER GET /api/v1/listings/me returns paginated listings")
    void getMyListingsReturns200() throws Exception {
        mockUser(PROVIDER_TOKEN, "provider-1", Role.PROVIDER);
        ListingSummaryResponse summary = new ListingSummaryResponse(
                "listing-1",
                "provider-1",
                "cat-sub",
                "Wiring Repair",
                "Licensed Home Electrical Repair",
                PricingModel.FIXED,
                new BigDecimal("3500.00"),
                90,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                "https://cdn.example.com/wiring-1.jpg",
                ListingStatus.DRAFT,
                false,
                List.of(),
                0.0,
                0,
                Instant.now(),
                Instant.now()
        );
        when(listingService.getMyListings(eq("provider-1"), eq(ListingStatus.DRAFT), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE + "/me?status=DRAFT")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("listing-1"))
                .andExpect(jsonPath("$.content[0].status").value("DRAFT"));
    }

    @Test
    @DisplayName("Provider dashboard summary, search filter, district geo endpoint, and 429 rate-limit response work cleanly")
    void dashboardSummarySearchDistrictsAndRateLimit() throws Exception {
        mockUser(PROVIDER_TOKEN, "provider-1", Role.PROVIDER);
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);

        when(listingService.getMyListingsSummary("provider-1"))
                .thenReturn(new ProviderListingDashboardSummaryResponse(5, 4, 1, 0, 0, 0, 0, 1));
        when(listingService.getMyListings(eq("provider-1"), eq(ListingStatus.ACTIVE), eq("Pipe"), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(listingService.getDistrictLocations())
                .thenReturn(List.of(new ProvinceGeoDto("Western", List.of(
                        new DistrictGeoDto("Colombo", "Western", 6.9271, 79.8612, 12)
                ))));

        mockMvc.perform(get(BASE + "/me/summary")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalListings").value(5))
                .andExpect(jsonPath("$.categoryInactiveCount").value(1));

        mockMvc.perform(get(BASE + "/me/summary")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(BASE + "/me?status=ACTIVE&search=Pipe")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/locations/districts")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Western"))
                .andExpect(jsonPath("$[0].districts[0].latitude").value(6.9271));

        when(listingService.createListing(eq("provider-1"), any()))
                .thenThrow(new RateLimitExceededException(120));

        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("Unauthenticated guests get 200 on public GET /listings/{id} and /listings/locations/districts, and 404 on hidden statuses")
    void guestCanReadPublicListingAndDistrictMetadataWithoutAuth() throws Exception {
        when(listingService.getListingById("listing-active"))
                .thenReturn(sampleResponse("listing-active", ListingStatus.ACTIVE));
        when(listingService.getListingById("listing-paused"))
                .thenReturn(sampleResponse("listing-paused", ListingStatus.PAUSED));
        when(listingService.getListingById("listing-hidden"))
                .thenThrow(new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found"));
        when(listingService.getDistrictLocations())
                .thenReturn(List.of(new ProvinceGeoDto("Western", List.of(
                        new DistrictGeoDto("Colombo", "Western", 6.9271, 79.8612, 12)
                ))));

        mockMvc.perform(get(BASE + "/listing-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("listing-active"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.availableForBooking").value(true));

        mockMvc.perform(get(BASE + "/listing-paused"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.availableForBooking").value(false));

        mockMvc.perform(get(BASE + "/listing-hidden"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LISTING_NOT_FOUND"));

        mockMvc.perform(get(BASE + "/locations/districts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Western"))
                .andExpect(jsonPath("$[0].districts[0].name").value("Colombo"));
    }

    @Test
    @DisplayName("FR-CUST-005: authenticated CUSTOMER GET /api/v1/listings/{id} returns listing or 404")
    void customerGetListingById() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(listingService.getListingById("listing-1"))
                .thenReturn(sampleResponse("listing-1", ListingStatus.PAUSED));
        when(listingService.getListingById("missing"))
                .thenThrow(new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found"));

        mockMvc.perform(get(BASE + "/listing-1")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.availableForBooking").value(false));

        mockMvc.perform(get(BASE + "/missing")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LISTING_NOT_FOUND"));
    }

    @Test
    @DisplayName("PUT & PATCH map ownership failures to 403 and state/category conflicts to 409")
    void updateAndStatusErrorMappings() throws Exception {
        mockUser(PROVIDER_TOKEN, "provider-1", Role.PROVIDER);
        when(listingService.updateListing(eq("provider-1"), eq("other-listing"), any()))
                .thenThrow(new AccessDeniedException("Not the owner"));
        when(listingService.updateListing(eq("provider-1"), eq("inactive-cat"), any()))
                .thenThrow(new ResourceConflictException("PARENT_CATEGORY_INACTIVE", "Parent inactive"));
        when(listingService.updateListingStatus(eq("provider-1"), eq("flagged-1"), any()))
                .thenThrow(new ResourceConflictException("INVALID_STATUS_TRANSITION", "Cannot activate flagged"));

        mockMvc.perform(put(BASE + "/other-listing")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(put(BASE + "/inactive-cat")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayloadJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PARENT_CATEGORY_INACTIVE"));

        mockMvc.perform(patch(BASE + "/flagged-1/status")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }
}
