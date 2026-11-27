package com.gamagerecruiters.marketplace.modules.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

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
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.ForgotPasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.ResendVerificationRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.ResetPasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.VerifyEmailRequest;
import com.gamagerecruiters.marketplace.modules.auth.security.CustomAccessDeniedHandler;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationEntryPoint;
import com.gamagerecruiters.marketplace.modules.auth.security.JwtAuthenticationFilter;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitExceededException;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitService;
import com.gamagerecruiters.marketplace.modules.auth.service.AuthService;
import com.gamagerecruiters.marketplace.modules.auth.service.EmailTokenService;
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

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private EmailTokenService emailTokenService;

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
    @DisplayName("Should return 400 VALIDATION_FAILED when full name contains digits")
    void shouldRejectRegistrationWhenFullNameContainsDigits() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "kalana test 14444",
                "valid@marketplace.lk",
                "+94771234567",
                "SecurePass123",
                Role.CUSTOMER,
                true,
                "v1.0"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    @DisplayName("Should return 400 VALIDATION_FAILED when password exceeds 72 characters")
    void shouldRejectRegistrationWhenPasswordExceeds72Chars() throws Exception {
        String longPassword = "A1a" + "b".repeat(70); // 73 chars
        RegisterRequest request = new RegisterRequest(
                "Kalana Dinuja",
                "valid@marketplace.lk",
                "+94771234567",
                longPassword,
                Role.CUSTOMER,
                true,
                "v1.0"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
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
                    "phone": "+94771234567",
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

    @Test
    @DisplayName("POST /api/v1/auth/login returns 429 RATE_LIMITED with Retry-After header when IP limit exceeded")
    void loginReturns429WhenIpRateLimited() throws Exception {
        org.mockito.Mockito.doThrow(new RateLimitExceededException(45))
                .when(rateLimitService).checkLoginIp(org.mockito.ArgumentMatchers.anyString());

        LoginRequest request = new LoginRequest("user@example.com", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."));

        verify(authService, never()).login(any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login returns 429 RATE_LIMITED with Retry-After header when account limit exceeded")
    void loginReturns429WhenAccountRateLimited() throws Exception {
        org.mockito.Mockito.doThrow(new RateLimitExceededException(60))
                .when(rateLimitService).checkLoginAccount(org.mockito.ArgumentMatchers.anyString());

        LoginRequest request = new LoginRequest("user@example.com", "Password123!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        verify(authService, never()).login(any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/register returns 429 RATE_LIMITED with Retry-After header when limit exceeded")
    void registerReturns429WhenRateLimited() throws Exception {
        org.mockito.Mockito.doThrow(new RateLimitExceededException(30))
                .when(rateLimitService).checkRegister(org.mockito.ArgumentMatchers.anyString());

        RegisterRequest request = new RegisterRequest(
                "Test User",
                "test@example.com",
                "+94771234567",
                "Password123!",
                Role.CUSTOMER,
                Boolean.TRUE,
                "v1.0"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        verify(authService, never()).register(any());
    }

    @Test
    @DisplayName("POST /api/v1/auth/change-password returns 429 RATE_LIMITED when sensitive limit exceeded")
    void changePasswordReturns429WhenSensitiveRateLimited() throws Exception {
        mockAuth("valid-token", "u-123", Role.CUSTOMER);
        org.mockito.Mockito.doThrow(new RateLimitExceededException(90))
                .when(rateLimitService).checkSensitive("u-123");

        ChangePasswordRequest request = new ChangePasswordRequest("OldPass123!", "NewPass123!");

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        verify(userInternalService, never()).changePassword(any(), any(), any());
    }

    private void wireRealRateLimiter(int loginIpLimit, int loginAccountLimit) {
        RateLimitService realLimiter = RateLimitService.createForTest(
                true,
                1000,
                loginIpLimit,
                loginAccountLimit,
                null
        );
        doAnswer(inv -> {
            realLimiter.checkLoginIp(inv.getArgument(0, String.class));
            return null;
        }).when(rateLimitService).checkLoginIp(anyString());

        doAnswer(inv -> {
            realLimiter.checkLoginAccount(inv.getArgument(0, String.class));
            return null;
        }).when(rateLimitService).checkLoginAccount(anyString());
    }

    @Test
    @DisplayName("Account limit of N blocks N+1 attempt for same email across different IPs")
    void accountRateLimitBlocksAcrossDifferentIps() throws Exception {
        wireRealRateLimiter(50, 3);
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new AuthResponse("access", "refresh", "Bearer", 900L, null, false));

        LoginRequest request = new LoginRequest("targeted@example.com", "Password123!");

        // Attempts 1 to 3 from different IPs should all succeed
        for (int i = 1; i <= 3; i++) {
            final String ip = "10.0.0." + i;
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(r -> { r.setRemoteAddr(ip); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // 4th attempt from yet another IP (10.0.0.4) must return 429 RATE_LIMITED with Retry-After header
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(r -> { r.setRemoteAddr("10.0.0.4"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."));
    }

    @Test
    @DisplayName("Email letter-casing variations share one account limit counter")
    void accountRateLimitNormalizesEmailVariations() throws Exception {
        wireRealRateLimiter(50, 3);
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new AuthResponse("access", "refresh", "Bearer", 900L, null, false));

        String[] emailVariants = {
                "Target@example.com",
                "TARGET@EXAMPLE.COM",
                "tArGeT@ExAmPlE.cOm"
        };

        // Attempts 1 to 3 with different letter-casings from different IPs succeed
        for (int i = 0; i < emailVariants.length; i++) {
            final String ip = "10.1.0." + (i + 1);
            LoginRequest req = new LoginRequest(emailVariants[i], "Password123!");
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(r -> { r.setRemoteAddr(ip); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }

        // 4th attempt with standard casing is blocked
        LoginRequest req4 = new LoginRequest("target@example.com", "Password123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(r -> { r.setRemoteAddr("10.1.0.4"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req4)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("normalizeEmail trims surrounding whitespace and converts to lower case")
    void normalizeEmailTrimsWhitespaceAndLowercases() {
        assertEquals("target@example.com", AuthController.normalizeEmail("  Target@EXAMPLE.COM  "));
        assertEquals("target@example.com", AuthController.normalizeEmail("target@example.com"));
        assertEquals("target@example.com", AuthController.normalizeEmail("TARGET@EXAMPLE.COM"));
        assertEquals("", AuthController.normalizeEmail(null));
        assertEquals("", AuthController.normalizeEmail("   "));
    }

    @Test
    @DisplayName("Per-IP rate limit blocks single IP trying many different accounts")
    void perIpLimitBlocksSingleIpAcrossDifferentAccounts() throws Exception {
        wireRealRateLimiter(4, 10);
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new AuthResponse("access", "refresh", "Bearer", 900L, null, false));

        final String clientIp = "192.168.1.150";

        // Attempts 1 to 4 with distinct emails from the same IP succeed
        for (int i = 1; i <= 4; i++) {
            LoginRequest req = new LoginRequest("different" + i + "@example.com", "Password123!");
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(r -> { r.setRemoteAddr(clientIp); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }

        // 5th attempt from the same IP with a brand-new email is blocked by IP limit
        LoginRequest req5 = new LoginRequest("fresh-unused@example.com", "Password123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(r -> { r.setRemoteAddr(clientIp); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req5)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("Unrelated email from another IP remains allowed when account limit is reached for one account")
    void unrelatedEmailFromAnotherIpRemainsAllowed() throws Exception {
        wireRealRateLimiter(50, 2);
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new AuthResponse("access", "refresh", "Bearer", 900L, null, false));

        // Drive targeted account to limit
        LoginRequest targetedReq = new LoginRequest("victim@example.com", "Password123!");
        for (int i = 1; i <= 2; i++) {
            final String ip = "10.2.0." + i;
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(r -> { r.setRemoteAddr(ip); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(targetedReq)))
                    .andExpect(status().isOk());
        }

        // 3rd attempt for victim is blocked
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(r -> { r.setRemoteAddr("10.2.0.3"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(targetedReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        // Unrelated email from an unrelated IP is still allowed
        LoginRequest innocentReq = new LoginRequest("innocent@example.com", "Password123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(innocentReq)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /verify-email returns 200 OK with success message when valid")
    void verifyEmail_success() throws Exception {
        VerifyEmailRequest request = new VerifyEmailRequest("valid-token-123");

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .with(r -> { r.setRemoteAddr("192.168.1.1"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email verified successfully"));

        verify(rateLimitService).checkVerifyEmail("192.168.1.1");
        verify(emailTokenService).verifyEmail("valid-token-123");
    }

    @Test
    @DisplayName("POST /verify-email returns 400 VALIDATION_FAILED when token is blank")
    void verifyEmail_blankToken_returns400() throws Exception {
        VerifyEmailRequest request = new VerifyEmailRequest("   ");

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(emailTokenService, never()).verifyEmail(anyString());
    }

    @Test
    @DisplayName("POST /verify-email returns 429 RATE_LIMITED when rate limit exceeded")
    void verifyEmail_rateLimited_returns429() throws Exception {
        doThrow(new RateLimitExceededException(45L))
                .when(rateLimitService).checkVerifyEmail(anyString());

        VerifyEmailRequest request = new VerifyEmailRequest("some-token");

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        verify(emailTokenService, never()).verifyEmail(anyString());
    }

    @Test
    @DisplayName("POST /resend-verification returns 200 OK with generic message when valid")
    void resendVerification_success() throws Exception {
        ResendVerificationRequest request = new ResendVerificationRequest("user@example.com");

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .with(r -> { r.setRemoteAddr("192.168.1.2"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If an account needs verification, a new email has been sent"));

        verify(rateLimitService).checkResendVerificationIp("192.168.1.2");
        verify(rateLimitService).checkResendVerificationEmail("user@example.com");
        verify(emailTokenService).resendVerification("user@example.com");
    }

    @Test
    @DisplayName("POST /resend-verification returns 400 VALIDATION_FAILED when email is malformed")
    void resendVerification_invalidEmail_returns400() throws Exception {
        ResendVerificationRequest request = new ResendVerificationRequest("not-an-email");

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(emailTokenService, never()).resendVerification(anyString());
    }

    @Test
    @DisplayName("POST /resend-verification returns 429 when IP rate limit exceeded")
    void resendVerification_ipRateLimited_returns429() throws Exception {
        doThrow(new RateLimitExceededException(60L))
                .when(rateLimitService).checkResendVerificationIp(anyString());

        ResendVerificationRequest request = new ResendVerificationRequest("user@example.com");

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        verify(emailTokenService, never()).resendVerification(anyString());
    }

    @Test
    @DisplayName("POST /forgot-password returns 200 OK with generic message when valid")
    void forgotPassword_success() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("user@example.com");

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .with(r -> { r.setRemoteAddr("192.168.1.3"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("If an account exists for this email, a reset link has been sent"));

        verify(rateLimitService).checkForgotPasswordIp("192.168.1.3");
        verify(rateLimitService).checkForgotPasswordEmail("user@example.com");
        verify(emailTokenService).forgotPassword("user@example.com");
    }

    @Test
    @DisplayName("POST /forgot-password returns 400 VALIDATION_FAILED when email is invalid")
    void forgotPassword_invalidEmail_returns400() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("invalid-email");

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(emailTokenService, never()).forgotPassword(anyString());
    }

    @Test
    @DisplayName("POST /forgot-password returns 429 when email rate limit exceeded")
    void forgotPassword_emailRateLimited_returns429() throws Exception {
        doThrow(new RateLimitExceededException(90L))
                .when(rateLimitService).checkForgotPasswordEmail(anyString());

        ForgotPasswordRequest request = new ForgotPasswordRequest("user@example.com");

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        verify(emailTokenService, never()).forgotPassword(anyString());
    }

    @Test
    @DisplayName("POST /reset-password returns 200 OK when valid")
    void resetPassword_success() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "NewStrongPass1!");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(r -> { r.setRemoteAddr("192.168.1.4"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successfully"));

        verify(rateLimitService).checkResetPassword("192.168.1.4");
        verify(emailTokenService).resetPassword("valid-token", "NewStrongPass1!");
    }

    @Test
    @DisplayName("POST /reset-password returns 400 VALIDATION_FAILED when token is blank or password is weak")
    void resetPassword_validationFailure_returns400() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("", "weak");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(emailTokenService, never()).resetPassword(anyString(), anyString());
    }

    @Test
    @DisplayName("POST /reset-password returns 429 when IP rate limit exceeded")
    void resetPassword_rateLimited_returns429() throws Exception {
        doThrow(new RateLimitExceededException(30L))
                .when(rateLimitService).checkResetPassword(anyString());

        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "NewStrongPass1!");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));

        verify(emailTokenService, never()).resetPassword(anyString(), anyString());
    }
}
