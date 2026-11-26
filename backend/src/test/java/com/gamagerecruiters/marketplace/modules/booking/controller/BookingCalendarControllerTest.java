package com.gamagerecruiters.marketplace.modules.booking.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarResponse;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingCalendarService;

@WebMvcTest({BookingCalendarController.class, CalendarFeedController.class})
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class BookingCalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private BookingCalendarService calendarService;

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
    @DisplayName("PROVIDER calendar view → 200; CUSTOMER → 403")
    void calendar() throws Exception {
        mockUser("prov-token", "prov-1", Role.PROVIDER);
        when(calendarService.calendar("2026-10-20", "2026-10-26")).thenReturn(
                new CalendarResponse("2026-10-20", "2026-10-26", "Asia/Colombo", List.of(), List.of()));

        mockMvc.perform(get("/api/v1/bookings/calendar").param("from", "2026-10-20").param("to", "2026-10-26")
                        .header("Authorization", "Bearer prov-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Colombo"));

        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        mockMvc.perform(get("/api/v1/bookings/calendar").header("Authorization", "Bearer cust-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(".ics download: text/calendar attachment")
    void export() throws Exception {
        mockUser("cust-token", "cust-1", Role.CUSTOMER);
        when(calendarService.exportIcs()).thenReturn("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");

        mockMvc.perform(get("/api/v1/bookings/calendar/export.ics").header("Authorization", "Bearer cust-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"localserve-bookings.ics\""))
                .andExpect(content().contentTypeCompatibleWith("text/calendar"));
    }

    @Test
    @DisplayName("Feed is public (calendar apps cannot log in); unknown link → 404")
    void publicFeed() throws Exception {
        String token = "A".repeat(43);
        when(calendarService.feed(token)).thenReturn("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
        when(calendarService.feed("B".repeat(43)))
                .thenThrow(new ResourceNotFoundException("CALENDAR_FEED_NOT_FOUND", "Calendar link not found"));

        mockMvc.perform(get("/api/v1/calendar/feed/" + token + ".ics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("text/calendar"));
        mockMvc.perform(get("/api/v1/calendar/feed/" + "B".repeat(43) + ".ics"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Subscription management needs login")
    void subscriptionNeedsLogin() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/calendar/subscription")).andExpect(status().isUnauthorized());
        verifyNoInteractions(calendarService);
    }
}
