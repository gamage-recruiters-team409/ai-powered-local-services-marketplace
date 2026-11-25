package com.gamagerecruiters.marketplace.modules.notification.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.gamagerecruiters.marketplace.modules.notification.dto.NotificationPageResponse;
import com.gamagerecruiters.marketplace.modules.notification.dto.UnreadCountResponse;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationInboxService;

@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class NotificationControllerAccessTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private TokenService tokenService;
    @MockitoBean private UserInternalService userInternalService;
    @MockitoBean private NotificationInboxService inbox;

    @Test void guestGetsUnauthorizedOnAllNotificationEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/notifications/n1/read")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/notifications/read-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inbox);
    }

    @ParameterizedTest @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER", "ADMIN"})
    void authenticatedCustomerProviderAndAdminCanUseAllNotificationEndpoints(Role role) throws Exception {
        when(inbox.listMine(false, 0, 20)).thenReturn(new NotificationPageResponse(List.of(), 0, 20, 0, 0));
        when(inbox.unreadCount()).thenReturn(2L);
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(role))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/notifications/n1/read").header("Authorization", bearer(role))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", bearer(role))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(role))).andExpect(status().isOk());
    }

    @Test void invalidFiltersReturn400WithoutCallingService() throws Exception {
        String auth = bearer(Role.CUSTOMER);
        mockMvc.perform(get("/api/v1/notifications").param("page", "-1").header("Authorization", auth)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications").param("size", "0").header("Authorization", auth)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications").param("size", "no").header("Authorization", auth)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/notifications").param("unread", "yes").header("Authorization", auth)).andExpect(status().isBadRequest());
        verifyNoInteractions(inbox);
    }

    @Test void listPassesExactArgumentsAndCounterResponsesHaveExpectedFields() throws Exception {
        when(inbox.listMine(true, 3, 7)).thenReturn(new NotificationPageResponse(List.of(), 3, 7, 0, 0));
        when(inbox.markAllRead()).thenReturn(5L);
        when(inbox.unreadCount()).thenReturn(9L);
        String auth = bearer(Role.CUSTOMER);
        mockMvc.perform(get("/api/v1/notifications").param("page", "3").param("size", "7").param("unread", "true").header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/notifications/notification-9/read").header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", auth)).andExpect(jsonPath("$.updated").value(5));
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", auth)).andExpect(jsonPath("$.unreadCount").value(9));
        verify(inbox).listMine(true, 3, 7);
        verify(inbox).markRead("notification-9");
        verify(inbox).markAllRead();
        verify(inbox).unreadCount();
    }

    private String bearer(Role role) {
        String token = "token-" + role.name(); String userId = "user-" + role.name();
        when(tokenService.validateToken(token)).thenReturn(true); when(tokenService.getUserIdFromToken(token)).thenReturn(userId); when(tokenService.getRoleFromToken(token)).thenReturn(role);
        User user = new User(); user.setId(userId); user.setEmail(userId + "@test.local"); user.setRole(role); user.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(user); return "Bearer " + token;
    }
}
