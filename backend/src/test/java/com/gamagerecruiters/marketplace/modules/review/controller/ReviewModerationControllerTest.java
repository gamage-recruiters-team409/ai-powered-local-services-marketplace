package com.gamagerecruiters.marketplace.modules.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.review.domain.ReportReason;
import com.gamagerecruiters.marketplace.modules.review.domain.ReportStatus;
import com.gamagerecruiters.marketplace.modules.review.domain.ReviewStatus;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationPageResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ModerationReviewResponse;
import com.gamagerecruiters.marketplace.modules.review.dto.ReviewReportResponse;
import com.gamagerecruiters.marketplace.modules.review.moderation.ModerationQueueFilter;
import com.gamagerecruiters.marketplace.modules.review.moderation.ReviewModerationService;

/** Role access and validation for the report and moderation endpoints, through the real security setup. */
@WebMvcTest({ReviewModerationController.class, ReviewReportController.class})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class ReviewModerationControllerTest {

    private static final String QUEUE = "/api/v1/admin/reviews";
    private static final String REMOVE_BODY = "{\"reasonCode\":\"CONTACT_DETAILS\",\"note\":\"Phone number in text\"}";
    private static final String REPORT_BODY = "{\"reason\":\"SPAM\",\"details\":\"Advert for another company\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private ReviewModerationService moderationService;

    private String bearer(Role role) {
        String token = "token-" + role.name();
        String userId = "user-" + role.name();
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user);
        return "Bearer " + token;
    }

    private static ModerationReviewResponse review(ReviewStatus status) {
        return new ModerationReviewResponse("rev-1", "booking-1", "listing-1", "prov-1", "Kamal", "cust-1", "Amaya",
                2, "Call 0771234567", status, List.of(), 0, false, null, null, Instant.parse("2026-11-18T05:00:00Z"));
    }

    @Test
    @DisplayName("Moderation endpoints return 401 without a token")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(QUEUE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(QUEUE + "/rev-1/remove").contentType(MediaType.APPLICATION_JSON).content(REMOVE_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(moderationService);
    }

    @Test
    @DisplayName("MODERATOR can read the queue (default filter ALL)")
    void moderatorReadsQueue() throws Exception {
        when(moderationService.getQueue(ModerationQueueFilter.ALL, 0, 20))
                .thenReturn(new ModerationPageResponse(List.of(review(ReviewStatus.AWAITING_MODERATION)), 0, 20, 1, 1));

        mockMvc.perform(get(QUEUE).header("Authorization", bearer(Role.MODERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].customerName").value("Amaya"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("SUPER_ADMIN can view the queue read-only but cannot decide (403)")
    void superAdminReadOnly() throws Exception {
        when(moderationService.getQueue(any(), anyInt(), anyInt()))
                .thenReturn(new ModerationPageResponse(List.of(), 0, 20, 0, 0));
        String auth = bearer(Role.SUPER_ADMIN);

        mockMvc.perform(get(QUEUE).header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(post(QUEUE + "/rev-1/remove").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(REMOVE_BODY))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER", "ADMIN", "SUPPORT_ADMIN", "FINANCE_ADMIN"})
    @DisplayName("Other roles get 403 on every moderation endpoint")
    void otherRolesForbidden(Role role) throws Exception {
        String auth = bearer(role);
        mockMvc.perform(get(QUEUE).header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(get(QUEUE + "/rev-1").header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(post(QUEUE + "/rev-1/publish").header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(post(QUEUE + "/rev-1/remove").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(REMOVE_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(moderationService);
    }

    @Test
    @DisplayName("MODERATOR removes with a reason; the moderator ID comes from the token")
    void moderatorRemoves() throws Exception {
        when(moderationService.remove(eq("user-MODERATOR"), eq("rev-1"), any())).thenReturn(review(ReviewStatus.REMOVED));

        mockMvc.perform(post(QUEUE + "/rev-1/remove").header("Authorization", bearer(Role.MODERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(REMOVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"));
    }

    @Test
    @DisplayName("Remove without a reason returns 400; an unknown queue filter returns 400 INVALID_FILTER")
    void validation() throws Exception {
        String auth = bearer(Role.MODERATOR);
        mockMvc.perform(post(QUEUE + "/rev-1/remove").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(QUEUE).param("filter", "EVERYTHING").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FILTER"));
    }

    @Test
    @DisplayName("MODERATOR publishes without a body (note is optional)")
    void publishWithoutBody() throws Exception {
        when(moderationService.publish(eq("user-MODERATOR"), eq("rev-1"), any())).thenReturn(review(ReviewStatus.PUBLISHED));

        mockMvc.perform(post(QUEUE + "/rev-1/publish").header("Authorization", bearer(Role.MODERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("Customers and providers can report a review (201)")
    void reportAllowed(Role role) throws Exception {
        when(moderationService.reportReview(eq("user-" + role.name()), eq(role), eq("rev-1"), any()))
                .thenReturn(new ReviewReportResponse("rep-1", "rev-1", role, ReportReason.SPAM, "Advert", ReportStatus.OPEN,
                        Instant.parse("2026-11-18T06:00:00Z")));

        mockMvc.perform(post("/api/v1/reviews/rev-1/reports").header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
        verify(moderationService).reportReview(eq("user-" + role.name()), eq(role), eq("rev-1"), any());
    }

    @Test
    @DisplayName("Report: admins get 403, a missing reason gets 400")
    void reportRules() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/rev-1/reports").header("Authorization", bearer(Role.MODERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/reviews/rev-1/reports").header("Authorization", bearer(Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"details\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
