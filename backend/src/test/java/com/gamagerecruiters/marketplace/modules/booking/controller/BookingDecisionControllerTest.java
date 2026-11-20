package com.gamagerecruiters.marketplace.modules.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDecisionResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.ProviderBookingDecisionService;

@WebMvcTest(BookingDecisionController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class BookingDecisionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private ProviderBookingDecisionService decisionService;

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
    @DisplayName("PROVIDER accepts → 200 AWAITING_PAYMENT with paymentDueAt")
    void accept() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);
        when(decisionService.accept("b-1")).thenReturn(new BookingDecisionResponse("b-1", "BK-1",
                BookingStatus.AWAITING_PAYMENT, Instant.parse("2026-11-20T04:00:00Z"),
                Instant.parse("2026-11-20T04:15:00Z")));

        mockMvc.perform(post("/api/v1/bookings/b-1/accept").header("Authorization", "Bearer prov-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.paymentDueAt").value("2026-11-20T04:15:00Z"));
    }

    @Test
    @DisplayName("CUSTOMER cannot accept (403)")
    void customerCannotAccept() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(post("/api/v1/bookings/b-1/accept").header("Authorization", "Bearer cust-token"))
                .andExpect(status().isForbidden());
        verify(decisionService, never()).accept(any());
    }

    @Test
    @DisplayName("Not logged in → 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/b-1/accept")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PROVIDER declines with a reason → 200 DECLINED")
    void decline() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);
        when(decisionService.decline(eq("b-1"), any())).thenReturn(new BookingDecisionResponse("b-1", "BK-1",
                BookingStatus.DECLINED, null, null));

        mockMvc.perform(post("/api/v1/bookings/b-1/decline").header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOT_AVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
    }

    @Test
    @DisplayName("Decline without a reason → 400 VALIDATION_FAILED")
    void declineWithoutReason() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);

        mockMvc.perform(post("/api/v1/bookings/b-1/decline").header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verify(decisionService, never()).decline(any(), any());
    }

    @Test
    @DisplayName("Unknown reason value → 400")
    void declineUnknownReason() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);

        mockMvc.perform(post("/api/v1/bookings/b-1/decline").header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BORED\"}"))
                .andExpect(status().isBadRequest());
        verify(decisionService, never()).decline(any(), any());
    }
}
