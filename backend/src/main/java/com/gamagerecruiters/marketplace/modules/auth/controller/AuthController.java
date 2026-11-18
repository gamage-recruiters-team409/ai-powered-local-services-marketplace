package com.gamagerecruiters.marketplace.modules.auth.controller;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.ForgotPasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LogoutRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.MessageResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.ResendVerificationRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.ResetPasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.VerifyEmailRequest;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.RateLimitService;
import com.gamagerecruiters.marketplace.modules.auth.service.AuthService;
import com.gamagerecruiters.marketplace.modules.auth.service.EmailTokenService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/auth")
public class AuthController {

    private final AuthService authService;
    private final UserInternalService userInternalService;
    private final RateLimitService rateLimitService;
    private final EmailTokenService emailTokenService;

    public AuthController(
            AuthService authService,
            UserInternalService userInternalService,
            RateLimitService rateLimitService,
            EmailTokenService emailTokenService
    ) {
        this.authService = authService;
        this.userInternalService = userInternalService;
        this.rateLimitService = rateLimitService;
        this.emailTokenService = emailTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIp(httpRequest);
        rateLimitService.checkRegister(clientIp);

        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIp(httpRequest);
        rateLimitService.checkLoginIp(clientIp);

        String normalizedEmail = normalizeEmail(request.email());
        rateLimitService.checkLoginAccount(normalizedEmail);

        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        String currentUserId = CurrentUser.getUserId();
        authService.logout(currentUserId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        String currentUserId = CurrentUser.getUserId();
        rateLimitService.checkSensitive(currentUserId);
        userInternalService.changePassword(currentUserId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIp(httpRequest);
        rateLimitService.checkVerifyEmail(clientIp);

        emailTokenService.verifyEmail(request.token());
        return ResponseEntity.ok(new MessageResponse("Email verified successfully"));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIp(httpRequest);
        rateLimitService.checkResendVerificationIp(clientIp);

        String normalizedEmail = normalizeEmail(request.email());
        rateLimitService.checkResendVerificationEmail(normalizedEmail);

        emailTokenService.resendVerification(request.email());
        return ResponseEntity.ok(new MessageResponse("If an account needs verification, a new email has been sent"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIp(httpRequest);
        rateLimitService.checkForgotPasswordIp(clientIp);

        String normalizedEmail = normalizeEmail(request.email());
        rateLimitService.checkForgotPasswordEmail(normalizedEmail);

        emailTokenService.forgotPassword(request.email());
        return ResponseEntity.ok(new MessageResponse("If an account exists for this email, a reset link has been sent"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIp(httpRequest);
        rateLimitService.checkResetPassword(clientIp);

        emailTokenService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(new MessageResponse("Password reset successfully"));
    }

    // Client IP must be obtained from request.getRemoteAddr() only. Do NOT trust X-Forwarded-For
    // directly as it can be spoofed by callers. When deploying behind a trusted reverse proxy,
    // configure server.forward-headers-strategy properly so Spring sets remoteAddr safely.
    private String getClientIp(HttpServletRequest request) {
        return request != null && request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    static String normalizeEmail(String email) {
        return email != null ? email.trim().toLowerCase(Locale.ROOT) : "";
    }
}
