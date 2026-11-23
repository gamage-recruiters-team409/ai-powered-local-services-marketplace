package com.gamagerecruiters.marketplace.modules.auth.service;

import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.CurrentUserResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LogoutRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.WebSocketPrincipal;

public interface AuthService {

    RegisterResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    void logout(String currentUserId, LogoutRequest request);

    CurrentUserResponse getCurrentUser(String currentUserId);

    /**
     * Authenticates a WebSocket connection using the provided access token.
     * Enforces active account status, current database role verification,
     * and rejects expired, malformed, non-access, or must-change-password tokens.
     *
     * @param token raw access token extracted from WebSocket subprotocol
     * @return {@link WebSocketPrincipal} containing userId and current database role
     * @throws org.springframework.security.authentication.BadCredentialsException if validation fails
     */
    WebSocketPrincipal validateAccessToken(String token);
}
