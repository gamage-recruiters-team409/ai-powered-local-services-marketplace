package com.gamagerecruiters.marketplace.modules.booking.controller;

import static org.mockito.ArgumentMatchers.any;
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
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateResult;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateWithBookingsRequest;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingBlockDateService;

@WebMvcTest(BookingBlockDateController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class BookingBlockDateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private BookingBlockDateService blockDateService;

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
    @DisplayName("PROVIDER blocks a date with decisions → 201")
    void block() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);
        when(blockDateService.blockWithBookings(any(BlockDateWithBookingsRequest.class))).thenReturn(
                new BlockDateResult(new BlockedDateResponse("bd-1", "2026-12-01", BlockReason.PERSONAL, null,
                        Instant.parse("2026-11-20T04:00:00Z")), List.of("p"), List.of("k"), List.of()));

        mockMvc.perform(post("/api/v1/bookings/blocked-dates").header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-12-01\",\"reason\":\"PERSONAL\","
                                + "\"decisions\":[{\"bookingId\":\"k\",\"action\":\"KEEP\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blockedDate.id").value("bd-1"))
                .andExpect(jsonPath("$.declinedBookingIds[0]").value("p"));
    }

    @Test
    @DisplayName("Bad date format or unknown action → 400, service not called")
    void invalidBody() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);

        mockMvc.perform(post("/api/v1/bookings/blocked-dates").header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"01-12-2026\",\"reason\":\"PERSONAL\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/bookings/blocked-dates").header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-12-01\",\"reason\":\"PERSONAL\","
                                + "\"decisions\":[{\"bookingId\":\"k\",\"action\":\"CANCEL\"}]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(blockDateService);
    }

    @Test
    @DisplayName("CUSTOMER → 403 on impact and block")
    void customerForbidden() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(get("/api/v1/bookings/blocked-dates/impact").param("date", "2026-12-01")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(blockDateService);
    }
}
