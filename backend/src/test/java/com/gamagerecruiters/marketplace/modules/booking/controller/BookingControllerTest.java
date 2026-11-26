package com.gamagerecruiters.marketplace.modules.booking.controller;

import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
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
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingRequestService;

@WebMvcTest(BookingController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class BookingControllerTest {

    private static final String URL = "/api/v1/bookings";
    private static final String VALID_BODY = """
            {"providerId":"prov-1","listingId":"listing-1","slotStart":"2026-11-23T03:30:00Z","addOnIds":[],
             "address":{"line1":"12 Temple Road","city":"Nugegoda","district":"Colombo","postalCode":"10250"},
             "notes":"Gate code 1234"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private BookingRequestService bookingRequestService;

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
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PROVIDER cannot create a booking request (403)")
    void providerForbidden() throws Exception {
        mockUser("prov-token", "prov-9", Role.PROVIDER);

        mockMvc.perform(post(URL).header("Authorization", "Bearer prov-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        verify(bookingRequestService, never()).createBookingRequest(any());
    }

    @Test
    @DisplayName("CUSTOMER creates a booking request (201, PENDING_PROVIDER)")
    void customerCreates() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(bookingRequestService.createBookingRequest(any())).thenReturn(new BookingResponse(
                "booking-1", "BK-20261120-ABCDEF", BookingStatus.PENDING_PROVIDER, "cust-1", "prov-1", "listing-1",
                Instant.parse("2026-11-23T03:30:00Z"), Instant.parse("2026-11-23T04:30:00Z"),
                "2026-11-23", "09:00", "10:00", 60,
                new BookingAddressRequest("12 Temple Road", null, "Nugegoda", "Colombo", "10250"),
                "Gate code 1234", Instant.parse("2026-11-20T14:30:00Z"), Instant.parse("2026-11-20T02:30:00Z"),
                "Home Cleaning", new java.math.BigDecimal("5000.00"), "LKR"));

        mockMvc.perform(post(URL).header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ref").value("BK-20261120-ABCDEF"))
                .andExpect(jsonPath("$.status").value("PENDING_PROVIDER"))
                .andExpect(jsonPath("$.localStart").value("09:00"))
                .andExpect(jsonPath("$.serviceTitle").value("Home Cleaning"))
                .andExpect(jsonPath("$.totalPrice").value(5000.00))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    @Test
    @DisplayName("Missing address returns 400 VALIDATION_FAILED")
    void missingAddress() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(post(URL).header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"prov-1\",\"listingId\":\"l-1\",\"slotStart\":\"2026-11-23T03:30:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verify(bookingRequestService, never()).createBookingRequest(any());
    }

    @Test
    @DisplayName("Invalid slotStart format returns 400 MALFORMED_REQUEST")
    void malformedSlotStart() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);

        mockMvc.perform(post(URL).header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("2026-11-23T03:30:00Z", "23-11-2026 09:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("Slot taken returns 409 SLOT_UNAVAILABLE")
    void slotTaken() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(bookingRequestService.createBookingRequest(any()))
                .thenThrow(new ResourceConflictException("SLOT_UNAVAILABLE", "taken"));

        mockMvc.perform(post(URL).header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLOT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Booking creation gated (dependencies not integrated) returns 503 BOOKING_CREATION_UNAVAILABLE")
    void creationGated() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(bookingRequestService.createBookingRequest(any())).thenThrow(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_CREATION_UNAVAILABLE", "not available"));

        mockMvc.perform(post(URL).header("Authorization", "Bearer cust-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("BOOKING_CREATION_UNAVAILABLE"));
    }
}
