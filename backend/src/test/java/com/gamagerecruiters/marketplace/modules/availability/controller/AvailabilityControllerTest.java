package com.gamagerecruiters.marketplace.modules.availability.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;

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
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;
import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursResponse;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityService;

@WebMvcTest(AvailabilityController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class AvailabilityControllerTest {

    private static final String BASE = "/api/v1/availability/me";
    private static final String PROVIDER_TOKEN = "valid-provider-token";
    private static final String CUSTOMER_TOKEN = "valid-customer-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private AvailabilityService availabilityService;

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

    private static String validWeekJson(int bufferMinutes) {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (days.length() > 0) {
                days.append(',');
            }
            days.append("{\"dayOfWeek\":\"").append(day)
                    .append("\",\"enabled\":true,\"startTime\":\"09:00\",\"endTime\":\"17:00\"}");
        }
        return "{\"weeklyHours\":[" + days + "],\"bufferMinutes\":" + bufferMinutes + "}";
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("CUSTOMER cannot read provider availability (403)")
    void customerGets403() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        verify(availabilityService, never()).getAvailability(any());
    }

    @Test
    @DisplayName("PROVIDER gets own availability (200)")
    void providerGetsOwnAvailability() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(availabilityService.getAvailability("prov-1")).thenReturn(new AvailabilityResponse(
                "prov-1",
                List.of(new WeeklyHoursResponse(DayOfWeek.MONDAY, true, "09:00", "17:00")),
                30,
                null));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("prov-1"))
                .andExpect(jsonPath("$.bufferMinutes").value(30))
                .andExpect(jsonPath("$.weeklyHours[0].dayOfWeek").value("MONDAY"));
    }

    @Test
    @DisplayName("PROVIDER update with buffer over 120 returns 400 VALIDATION_FAILED")
    void updateRejectsInvalidBuffer() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(put(BASE)
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWeekJson(200)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verify(availabilityService, never()).updateAvailability(any(), any());
    }

    @Test
    @DisplayName("PROVIDER update with a valid week returns 200")
    void updateAcceptsValidWeek() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(availabilityService.updateAvailability(eq("prov-1"), any()))
                .thenReturn(new AvailabilityResponse("prov-1", List.of(), 45, Instant.parse("2026-11-10T04:00:00Z")));

        mockMvc.perform(put(BASE)
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWeekJson(45)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bufferMinutes").value(45));
    }

    @Test
    @DisplayName("PROVIDER creates a blocked date (201)")
    void createBlockedDateReturns201() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);
        when(availabilityService.createBlockedDate(eq("prov-1"), any()))
                .thenReturn(new BlockedDateResponse("bd-1", "2026-11-20", BlockReason.HOLIDAY, null, null));

        mockMvc.perform(post(BASE + "/blocked-dates")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-20\",\"reason\":\"HOLIDAY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("bd-1"))
                .andExpect(jsonPath("$.date").value("2026-11-20"));
    }

    @Test
    @DisplayName("Blocked date with wrong date format returns 400")
    void createBlockedDateRejectsBadFormat() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(post(BASE + "/blocked-dates")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"20-11-2026\",\"reason\":\"HOLIDAY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("CUSTOMER cannot delete a blocked date (403)")
    void customerCannotDeleteBlockedDate() throws Exception {
        mockUser(CUSTOMER_TOKEN, "cust-1", Role.CUSTOMER);

        mockMvc.perform(delete(BASE + "/blocked-dates/bd-1")
                        .header("Authorization", "Bearer " + CUSTOMER_TOKEN))
                .andExpect(status().isForbidden());
        verify(availabilityService, never()).deleteBlockedDate(any(), any());
    }

    @Test
    @DisplayName("PROVIDER deletes own blocked date (204)")
    void providerDeletesBlockedDate() throws Exception {
        mockUser(PROVIDER_TOKEN, "prov-1", Role.PROVIDER);

        mockMvc.perform(delete(BASE + "/blocked-dates/bd-1")
                        .header("Authorization", "Bearer " + PROVIDER_TOKEN))
                .andExpect(status().isNoContent());
        verify(availabilityService).deleteBlockedDate("prov-1", "bd-1");
    }
}
