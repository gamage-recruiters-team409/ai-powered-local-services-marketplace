package com.gamagerecruiters.marketplace.modules.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ProviderReplyResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.RatingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.service.ReviewService;

@WebMvcTest({ ReviewController.class, ProviderReviewController.class })
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class ReviewControllerTest {

    private static final String CUSTOMER_TOKEN = "valid-customer-token";
    private static final String PROVIDER_TOKEN = "valid-provider-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private ReviewService reviewService;

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

    private static ReviewResponse review(ProviderReplyResponse reply) {
        return new ReviewResponse("rev-1", "booking-1", "listing-1", "prov-1", 5, "Great",
                ReviewStatus.PUBLISHED, reply, false, null, Instant.parse("2026-11-11T10:00:00Z"));
    }

    @Test
    @DisplayName("Unauthenticated review submit returns 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"booking-1\",\"rating\":5}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CUSTOMER submits a review (201)")
    void customerCreatesReview() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(reviewService.createReview(eq("cust-1"), any())).thenReturn(review(null));

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"booking-1\",\"rating\":5,\"comment\":\"Great\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("rev-1"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("Rating 6 returns 400 VALIDATION_FAILED")
    void rejectsInvalidRating() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"booking-1\",\"rating\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verify(reviewService, never()).createReview(anyString(), any());
    }

    @Test
    @DisplayName("PROVIDER cannot submit a review (403)")
    void providerCannotCreateReview() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"booking-1\",\"rating\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Business rule errors are returned with their code (409 REVIEW_WINDOW_CLOSED)")
    void conflictIsReturned() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(reviewService.createReview(eq("cust-1"), any())).thenThrow(
                new ResourceConflictException("REVIEW_WINDOW_CLOSED", "Reviews can be submitted within 7 days of completion"));

        mockMvc.perform(post("/api/v1/reviews")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"booking-1\",\"rating\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REVIEW_WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("CUSTOMER edits own review (200)")
    void customerEditsReview() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(reviewService.updateReview(eq("cust-1"), eq("rev-1"), any())).thenReturn(review(null));

        mockMvc.perform(put("/api/v1/reviews/rev-1")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"comment\":\"Good\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CUSTOMER lists own reviews (200)")
    void customerListsOwnReviews() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(reviewService.getMyReviews("cust-1", 0, 10))
                .thenReturn(new ReviewPageResponse(List.of(review(null)), 0, 10, 1, 1));

        mockMvc.perform(get("/api/v1/reviews/me").header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value("rev-1"));
    }

    @Test
    @DisplayName("PROVIDER replies to a review (200); CUSTOMER cannot (403)")
    void replyIsProviderOnly() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(reviewService.replyToReview(eq("prov-1"), eq("rev-1"), any()))
                .thenReturn(review(new ProviderReplyResponse("Thank you!", Instant.parse("2026-11-11T11:00:00Z"))));

        mockMvc.perform(put("/api/v1/reviews/rev-1/reply")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Thank you!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerReply.text").value("Thank you!"));

        mockMvc.perform(put("/api/v1/reviews/rev-1/reply")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Empty reply text returns 400 VALIDATION_FAILED")
    void rejectsBlankReply() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(put("/api/v1/reviews/rev-1/reply")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PROVIDER lists own reviews (200)")
    void providerListsOwnReviews() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(reviewService.getProviderOwnReviews("prov-1", 0, 10))
                .thenReturn(new ReviewPageResponse(List.of(review(null)), 0, 10, 1, 1));

        mockMvc.perform(get("/api/v1/providers/me/reviews").header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].providerId").value("prov-1"));
    }

    @Test
    @DisplayName("Any signed-in user can read a provider's published reviews and rating summary")
    void publicReviewsAndSummary() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);
        when(reviewService.getPublishedReviews(eq("prov-1"), anyInt(), anyInt()))
                .thenReturn(new ReviewPageResponse(List.of(review(null)), 0, 10, 1, 1));
        when(reviewService.getRatingSummary("prov-1"))
                .thenReturn(new RatingSummaryResponse("prov-1", 4.6, 23, Map.of(5, 15L, 4, 6L, 3, 2L, 2, 0L, 1, 0L)));

        mockMvc.perform(get("/api/v1/providers/prov-1/reviews").header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rating").value(5));

        mockMvc.perform(get("/api/v1/providers/prov-1/rating-summary").header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.6))
                .andExpect(jsonPath("$.reviewCount").value(23));
    }
}
