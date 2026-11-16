package com.gamagerecruiters.marketplace.modules.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.common.exception.GlobalExceptionHandler;
import com.gamagerecruiters.marketplace.config.SecurityConfig;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.service.AuthService;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@WebMvcTest(AuthController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserInternalService userInternalService;

    private void mockAuth(String token, String userId, Role role) {
        when(tokenService.validateToken(token)).thenReturn(true);
        when(tokenService.getUserIdFromToken(token)).thenReturn(userId);
        when(tokenService.getEmailFromToken(token)).thenReturn(userId + "@marketplace.local");
        when(tokenService.getRoleFromToken(token)).thenReturn(role);

        User activeUser = new User();
        activeUser.setId(userId);
        activeUser.setEmail(userId + "@marketplace.local");
        activeUser.setRole(role);
        activeUser.setStatus(UserStatus.ACTIVE);
        when(userInternalService.getById(userId)).thenReturn(activeUser);
    }

    @Test
    @DisplayName("Should return 400 VALIDATION_FAILED when registration payload has validation errors")
    void shouldReturnValidationErrorsOnInvalidRegistration() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "",
                "invalid-email",
                "123",
                "weak",
                Role.CUSTOMER,
                false,
                ""
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("Should return 400 when login payload is empty")
    void shouldReturnBadRequestWhenLoginEmpty() throws Exception {
        LoginRequest request = new LoginRequest("", "");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("Should return 401 INVALID_CREDENTIALS when service throws BadCredentialsException")
    void shouldReturnUnauthorizedWhenInvalidCredentials() throws Exception {
        LoginRequest request = new LoginRequest("user@marketplace.local", "WrongPass123");

        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("Issue 8: Malformed JSON body on /api/v1/auth/login should return 400 MALFORMED_REQUEST")
    void shouldReturn400OnMalformedJsonLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"test@test.com\", invalidJson"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    @DisplayName("Issue 8: Malformed JSON body on /api/v1/auth/register should return 400 MALFORMED_REQUEST")
    void shouldReturn400OnMalformedJsonRegister() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    @DisplayName("Issue 8: Invalid enum value for role in register payload should return 400 instead of 500")
    void shouldReturn400OnInvalidRoleEnum() throws Exception {
        String invalidPayload = """
                {
                    "name": "Jane Doe",
                    "email": "jane@example.com",
                    "phone": "+1234567890",
                    "password": "Password123",
                    "role": "NOT_A_VALID_ROLE",
                    "acceptedTerms": true,
                    "policyVersion": "v1.0"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password with valid payload returns 204 No Content and passes authenticated user id")
    void shouldChangePasswordSuccessfully() throws Exception {
        mockAuth("valid-token", "u-123", Role.ADMIN);
        ChangePasswordRequest request = new ChangePasswordRequest("OldPass123", "NewPass123");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        verify(userInternalService).changePassword(eq("u-123"), eq("OldPass123"), eq("NewPass123"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password ignores any body userId field and uses authenticated principal")
    void shouldIgnoreBodyUserIdField() throws Exception {
        mockAuth("valid-token", "u-123", Role.CUSTOMER);
        String payload = """
                {
                    "userId": "attacker-id",
                    "currentPassword": "OldPass123",
                    "newPassword": "NewPass123"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNoContent());

        verify(userInternalService).changePassword(eq("u-123"), eq("OldPass123"), eq("NewPass123"));
        verify(userInternalService, never()).changePassword(eq("attacker-id"), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password without authentication returns 401 UNAUTHORIZED")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("OldPass123", "NewPass123");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(userInternalService, never()).changePassword(any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password with invalid or expired token returns 401 UNAUTHORIZED")
    void shouldReturn401WhenTokenIsInvalidOrExpired() throws Exception {
        when(tokenService.validateToken("expired-or-bad-token")).thenReturn(false);
        ChangePasswordRequest request = new ChangePasswordRequest("OldPass123", "NewPass123");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer expired-or-bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        verify(userInternalService, never()).changePassword(any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password with weak password returns 400 VALIDATION_FAILED and service is not called")
    void shouldRejectWeakNewPassword() throws Exception {
        mockAuth("valid-token", "u-123", Role.CUSTOMER);
        ChangePasswordRequest request = new ChangePasswordRequest("OldPass123", "weak");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(userInternalService, never()).changePassword(any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password with blank new password returns 400 VALIDATION_FAILED and service is not called")
    void shouldRejectBlankNewPassword() throws Exception {
        mockAuth("valid-token", "u-123", Role.CUSTOMER);
        ChangePasswordRequest request = new ChangePasswordRequest("OldPass123", "");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(userInternalService, never()).changePassword(any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password with blank current password returns 400 VALIDATION_FAILED and service is not called")
    void shouldRejectBlankCurrentPassword() throws Exception {
        mockAuth("valid-token", "u-123", Role.CUSTOMER);
        ChangePasswordRequest request = new ChangePasswordRequest("", "NewPass123");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(userInternalService, never()).changePassword(any(), any(), any());
    }
}
