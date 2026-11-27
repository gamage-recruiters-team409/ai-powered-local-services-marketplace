package com.gamagerecruiters.marketplace.modules.admin.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse.DisputeBreakdown;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAnalyticsSummaryResponse.MetricItem;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminReportResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.PlatformSettingsGroupResponse;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAnalyticsService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminReportsService;
import com.gamagerecruiters.marketplace.modules.admin.service.PlatformSettingsService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@WebMvcTest({PlatformSettingsController.class, AdminAnalyticsController.class, AdminReportsController.class})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class SuperAdminNewEndpointsAccessTest {

    private static final String SETTINGS_URL = "/api/v1/admin/settings";
    private static final String ANALYTICS_URL = "/api/v1/admin/analytics/summary";
    private static final String REPORTS_URL = "/api/v1/admin/reports/disputes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private PlatformSettingsService settingsService;

    @MockitoBean
    private AdminAnalyticsService analyticsService;

    @MockitoBean
    private AdminReportsService reportsService;

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

    @Test
    @DisplayName("Unauthenticated requests to settings are rejected (401)")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get(SETTINGS_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ANALYTICS_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(REPORTS_URL)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN", "CUSTOMER", "PROVIDER"})
    @DisplayName("Non-SUPER_ADMIN roles receive 403 on settings writes and Super Admin endpoints")
    void nonSuperAdminReturns403(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(get(SETTINGS_URL).header("Authorization", auth))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(SETTINGS_URL + "/booking")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"values\":{\"min_booking_notice_hours\":4}}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(ANALYTICS_URL).header("Authorization", auth))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(REPORTS_URL).header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SUPER_ADMIN can read settings, analytics summary and reports (200)")
    void superAdminGets200() throws Exception {
        String auth = bearer(Role.SUPER_ADMIN);

        PlatformSettingsGroupResponse groupRes = new PlatformSettingsGroupResponse("booking", 1L, "sys",
                Instant.now(), Map.of("min_booking_notice_hours", 3), List.of());
        when(settingsService.getAllGrouped()).thenReturn(Map.of("booking", groupRes));
        when(settingsService.getGroup("booking")).thenReturn(groupRes);

        mockMvc.perform(get(SETTINGS_URL).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.group").value("booking"));

        AdminAnalyticsSummaryResponse analyticsRes = new AdminAnalyticsSummaryResponse(
                MetricItem.unavailable("WP01"), MetricItem.unavailable("WP01"), MetricItem.unavailable("WP05"),
                MetricItem.unavailable("WP07"), MetricItem.unavailable("WP07"), MetricItem.unavailable("WP07"),
                MetricItem.available(5L, "DisputeService"), MetricItem.available(2, "VerificationAdminService"),
                MetricItem.available(3L, "AdminAccountService"), new DisputeBreakdown(2, 2, 1, 0), List.of()
        );
        when(analyticsService.getSummary()).thenReturn(analyticsRes);

        mockMvc.perform(get(ANALYTICS_URL).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openDisputes.value").value(5));

        AdminReportResponse reportRes = AdminReportResponse.available("disputes", "DisputeService",
                List.of(Map.of("date", "2026-11-20", "opened", 1)), Map.of("totalOpened", 1), 0, 20, 1, 1);
        when(reportsService.generateReport(eq("disputes"), any(), any(), anyInt(), anyInt())).thenReturn(reportRes);

        mockMvc.perform(get(REPORTS_URL).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("disputes"));
    }
}
