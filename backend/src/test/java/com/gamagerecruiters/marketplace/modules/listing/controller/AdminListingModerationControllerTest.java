package com.gamagerecruiters.marketplace.modules.listing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
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
import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.GeoPointDto;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.ListingSummaryResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;

@WebMvcTest(AdminListingModerationController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class AdminListingModerationControllerTest {

    private static final String BASE = "/api/v1/admin/listings";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private ListingService listingService;

    private String authenticateAs(Role role) {
        String token = "token-" + role.name().toLowerCase();
        String userId = "user-" + role.name().toLowerCase();
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User user = new User();
        user.setId(userId);
        user.setEmail(userId + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user);
        return token;
    }

    private ListingResponse flaggedResponse() {
        return new ListingResponse(
                "flagged-1",
                "provider-1",
                "cat-sub",
                "Wiring Repair",
                "cat-root",
                "Electrical Panel Repair",
                "Complete breaker panel inspection and repair service across Colombo.",
                PricingModel.FIXED,
                new BigDecimal("3500.00"),
                60,
                ExecutionModel.ON_SITE,
                "Colombo",
                "Dehiwala",
                new GeoPointDto(79.8612, 6.9271),
                15,
                List.of("Dehiwala"),
                List.of("https://cdn.example.com/1.jpg"),
                "https://cdn.example.com/1.jpg",
                List.of(),
                ListingStatus.ACTIVE,
                true,
                List.of(),
                null,
                Instant.now(),
                0.0,
                0,
                2L,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("Unauthenticated requests to admin moderation endpoints return 401 UNAUTHORIZED")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get(BASE + "/moderation"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch(BASE + "/flagged-1/moderation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MODERATOR can read queue/detail and approve or reject flagged listings")
    void moderatorCanViewAndEdit() throws Exception {
        String modToken = authenticateAs(Role.MODERATOR);
        ListingSummaryResponse summary = new ListingSummaryResponse(
                "flagged-1", "provider-1", "cat-sub", "Wiring Repair",
                "Electrical Panel Repair", PricingModel.FIXED, new BigDecimal("3500.00"),
                60, ExecutionModel.ON_SITE, "Colombo", "Dehiwala",
                "https://cdn.example.com/1.jpg", ListingStatus.FLAGGED, false,
                List.of("PHONE_NUMBER_DETECTED"), 0.0, 0, Instant.now(), Instant.now()
        );
        when(listingService.getModerationQueue(any(), any()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1));
        when(listingService.getListingForModeration("flagged-1")).thenReturn(flaggedResponse());
        when(listingService.moderateListing(eq("user-moderator"), eq("flagged-1"), any()))
                .thenReturn(flaggedResponse());

        mockMvc.perform(get(BASE + "/moderation")
                        .header("Authorization", "Bearer " + modToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("flagged-1"));

        mockMvc.perform(get(BASE + "/flagged-1")
                        .header("Authorization", "Bearer " + modToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("flagged-1"));

        mockMvc.perform(patch(BASE + "/flagged-1/moderation")
                        .header("Authorization", "Bearer " + modToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\"Verified clean\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("SUPER_ADMIN has read-only access (200 GET) but is denied writes (403 PATCH)")
    void superAdminIsReadOnlyOnModeration() throws Exception {
        String superToken = authenticateAs(Role.SUPER_ADMIN);
        when(listingService.getModerationQueue(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(listingService.getListingForModeration("flagged-1")).thenReturn(flaggedResponse());

        mockMvc.perform(get(BASE + "/moderation")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE + "/flagged-1")
                        .header("Authorization", "Bearer " + superToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch(BASE + "/flagged-1/moderation")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("Non-moderator roles receive 403 ACCESS_DENIED on both GET and PATCH moderation endpoints")
    void nonModeratorRolesForbidden(Role role) throws Exception {
        String token = authenticateAs(role);

        mockMvc.perform(get(BASE + "/moderation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(get(BASE + "/flagged-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(patch(BASE + "/flagged-1/moderation")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reason\":\"Leak\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Validation failure and invalid moderation state conflict map cleanly to 400 and 409")
    void moderationValidationAndConflict() throws Exception {
        String modToken = authenticateAs(Role.MODERATOR);
        when(listingService.moderateListing(eq("user-moderator"), eq("active-1"), any()))
                .thenThrow(new ResourceConflictException("INVALID_MODERATION_STATE", "Not flagged"));

        mockMvc.perform(patch(BASE + "/active-1/moderation")
                        .header("Authorization", "Bearer " + modToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());

        mockMvc.perform(patch(BASE + "/active-1/moderation")
                        .header("Authorization", "Bearer " + modToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_MODERATION_STATE"));
    }
}
