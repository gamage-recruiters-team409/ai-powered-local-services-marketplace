package com.gamagerecruiters.marketplace.modules.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.ChangePasswordRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LogoutRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterResponse;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.service.AuthService;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.API_V1 + "/auth")
public class AuthController {

    private final AuthService authService;
    private final UserInternalService userInternalService;

    public AuthController(AuthService authService, UserInternalService userInternalService) {
        this.authService = authService;
        this.userInternalService = userInternalService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
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
        userInternalService.changePassword(currentUserId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
