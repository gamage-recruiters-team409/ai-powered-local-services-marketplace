package com.gamagerecruiters.marketplace.modules.booking.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDetailsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingListItem;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPageResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingQueryService;

@WebMvcTest(BookingQueryController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class BookingQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private BookingQueryService queryService;

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

    private static BookingPageResponse<BookingListItem> emptyPage() {
        return new BookingPageResponse<>(List.of(), 0, 10, 0, 0);
    }

    @Test
    @DisplayName("CUSTOMER lists own bookings with a status filter → 200")
    void customerList() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        Set<BookingStatus> statuses = EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
        when(queryService.listCustomerBookings(statuses, 0, 10)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/bookings/customer").param("status", "CONFIRMED,COMPLETED")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(queryService).listCustomerBookings(statuses, 0, 10);
    }

    @Test
    @DisplayName("PROVIDER lists own bookings (defaults page 0, size 10) → 200")
    void providerList() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);
        when(queryService.listProviderBookings(null, 0, 10)).thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/bookings/provider").header("Authorization", "Bearer prov-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PROVIDER calling the customer list → 403, service not called")
    void providerOnCustomerList() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);

        mockMvc.perform(get("/api/v1/bookings/customer").header("Authorization", "Bearer prov-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("No token → 401")
    void noToken() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/customer")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("Unknown status value → 400, service not called")
    void badStatus() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(get("/api/v1/bookings/customer").param("status", "WRONG")
                        .header("Authorization", "Bearer cust-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS"));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("Booking details for a participant → 200")
    void details() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(queryService.getBookingDetails("b-1")).thenReturn(new BookingDetailsResponse("b-1", "BK-1",
                BookingStatus.CONFIRMED, "CUSTOMER", "lst-1", "AC Repair", "cust-1", "prov-1", null, null, null,
                null, null, null, 120, true, null, null, null, null, null, null, null, List.of(), null, 2));

        mockMvc.perform(get("/api/v1/bookings/b-1").header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value("BK-1"))
                .andExpect(jsonPath("$.viewerRole").value("CUSTOMER"));
    }

    @Test
    @DisplayName("ADMIN calling booking details → 403 (participants only)")
    void adminDetails() throws Exception {
        mockUser("admin-token", "admin-1", Role.ADMIN);

        mockMvc.perform(get("/api/v1/bookings/b-1").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(queryService);
    }
}
