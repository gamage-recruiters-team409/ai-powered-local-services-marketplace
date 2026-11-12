package com.gamagerecruiters.marketplace.modules.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
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
import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.TimeSlot;
import com.gamagerecruiters.marketplace.modules.booking.service.SlotService;

@WebMvcTest(BookingSlotController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class BookingSlotControllerTest {

    private static final String URL = "/api/v1/bookings/available-slots?providerId=p-1&date=2026-11-23&durationMinutes=60";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private SlotService slotService;

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

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CUSTOMER gets available slots (200)")
    void customerGetsSlots() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(slotService.getAvailableSlots("p-1", "2026-11-23", "60")).thenReturn(new AvailableSlotsResponse(
                "p-1", "2026-11-23", "Asia/Colombo", 60, 30, "WORKING",
                List.of(new TimeSlot(Instant.parse("2026-11-23T03:30:00Z"), Instant.parse("2026-11-23T04:30:00Z"),
                        "09:00", "10:00"))));

        mockMvc.perform(get(URL).header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayStatus").value("WORKING"))
                .andExpect(jsonPath("$.slots[0].localStart").value("09:00"))
                .andExpect(jsonPath("$.slots[0].start").value("2026-11-23T03:30:00Z"));
    }

    @Test
    @DisplayName("Admin role cannot use the customer slot endpoint (403)")
    void adminForbidden() throws Exception {
        mockUser("admin-token", "admin-1", Role.SUPPORT_ADMIN);

        mockMvc.perform(get(URL).header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
        verify(slotService, never()).getAvailableSlots(any(), any(), any());
    }

    @Test
    @DisplayName("Invalid input from the service returns 400 with the error code")
    void invalidInput() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(slotService.getAvailableSlots("p-1", "2026-11-23", "60"))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DURATION", "bad duration"));

        mockMvc.perform(get(URL).header("Authorization", "Bearer cust-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_DURATION"));
    }
}
