package com.gamagerecruiters.marketplace.modules.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleView;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingRescheduleService;

@WebMvcTest(BookingRescheduleController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class BookingRescheduleControllerTest {

    private static final Instant NEW_START = Instant.parse("2026-11-23T04:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private BookingRescheduleService rescheduleService;

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

    private static RescheduleResponse pendingResponse() {
        return new RescheduleResponse("b-1", "BK-1", BookingStatus.CONFIRMED, Instant.parse("2026-11-22T04:30:00Z"),
                Instant.parse("2026-11-22T06:30:00Z"),
                new RescheduleView("r-1", RescheduleStatus.PENDING, "CUSTOMER", Instant.parse("2026-11-22T04:30:00Z"),
                        NEW_START, Instant.parse("2026-11-23T06:30:00Z"), null, Instant.parse("2026-11-20T04:00:00Z"),
                        Instant.parse("2026-11-20T16:00:00Z")),
                2);
    }

    @Test
    @DisplayName("CUSTOMER proposes a new time → 200 PENDING")
    void request() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(rescheduleService.request(eq("b-1"), any(RescheduleBookingRequest.class))).thenReturn(pendingResponse());

        mockMvc.perform(post("/api/v1/bookings/b-1/reschedule").header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newSlotStart\":\"2026-11-23T04:30:00Z\",\"note\":\"Exam that day\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reschedule.status").value("PENDING"))
                .andExpect(jsonPath("$.remainingReschedules").value(2));
    }

    @Test
    @DisplayName("Missing newSlotStart → 400, service not called")
    void missingNewStart() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(post("/api/v1/bookings/b-1/reschedule").header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rescheduleService);
    }

    @Test
    @DisplayName("Note longer than 300 characters → 400")
    void noteTooLong() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(post("/api/v1/bookings/b-1/reschedule").header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newSlotStart\":\"2026-11-23T04:30:00Z\",\"note\":\"" + "a".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rescheduleService);
    }

    @Test
    @DisplayName("PROVIDER approves → 200")
    void approve() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);
        when(rescheduleService.approve("b-1")).thenReturn(pendingResponse());

        mockMvc.perform(post("/api/v1/bookings/b-1/reschedule/approve").header("Authorization", "Bearer prov-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN → 403; no token → 401")
    void accessRules() throws Exception {
        mockUser("admin-token", "admin-1", Role.ADMIN);

        mockMvc.perform(post("/api/v1/bookings/b-1/reschedule/reject").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/b-1/reschedule/withdraw"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(rescheduleService);
    }
}
