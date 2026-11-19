package com.gamagerecruiters.marketplace.modules.messaging.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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

import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccessPolicyService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.messaging.dto.ConversationPageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessagePageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.dto.MessageResponse;
import com.gamagerecruiters.marketplace.modules.messaging.service.MessageService;

@WebMvcTest(MessagingController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        AdminAccessPolicyService.class
})
@ActiveProfiles("test")
class MessagingControllerAccessTest {

    private static final String MESSAGES = "/api/v1/bookings/booking-1/messages";
    private static final String CONVERSATIONS = "/api/v1/conversations";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    @MockitoBean
    private MessageService messageService;

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

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    void customerAndProviderCanListConversations(Role role) throws Exception {
        when(messageService.listConversations(0, 20))
                .thenReturn(new ConversationPageResponse(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get(CONVERSATIONS).header("Authorization", bearer(role)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    void customerAndProviderCanListAndSendMessages(Role role) throws Exception {
        when(messageService.listMessages("booking-1", 0, 20))
                .thenReturn(new MessagePageResponse(List.of(), 0, 20, 0, 0));
        when(messageService.sendMessage("booking-1", "Hello"))
                .thenReturn(new MessageResponse("message-1", "conversation-1", "sender-1",
                        "Hello", com.gamagerecruiters.marketplace.modules.messaging.domain.MessageStatus.VISIBLE,
                        java.time.Instant.parse("2026-11-19T10:00:00Z")));

        mockMvc.perform(get(MESSAGES).header("Authorization", bearer(role)))
                .andExpect(status().isOk());
        mockMvc.perform(post(MESSAGES).header("Authorization", bearer(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hello\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void guestGetsUnauthorizedAndServiceIsNotCalled() throws Exception {
        mockMvc.perform(get(CONVERSATIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(MESSAGES)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(MESSAGES).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Hello\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(messageService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    void adminRolesAreForbiddenOnAllThreeEndpoints(Role role) throws Exception {
        String auth = bearer(role);

        mockMvc.perform(get(CONVERSATIONS).header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(get(MESSAGES).header("Authorization", auth)).andExpect(status().isForbidden());
        mockMvc.perform(post(MESSAGES).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Hello\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(messageService);
    }

    @Test
    void invalidSinceReturns400AndServiceIsNotCalled() throws Exception {
        mockMvc.perform(get(MESSAGES).param("since", "not-a-time")
                        .header("Authorization", bearer(Role.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SINCE"));
        verifyNoInteractions(messageService);
    }

    @Test
    void invalidPageReturns400AndServiceIsNotCalled() throws Exception {
        mockMvc.perform(get(MESSAGES).param("page", "abc")
                        .header("Authorization", bearer(Role.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"));
        verifyNoInteractions(messageService);
    }

    @Test
    void invalidSizeReturns400AndServiceIsNotCalled() throws Exception {
        mockMvc.perform(get(MESSAGES).param("size", "abc")
                        .header("Authorization", bearer(Role.PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION"));
        verifyNoInteractions(messageService);
    }
}
