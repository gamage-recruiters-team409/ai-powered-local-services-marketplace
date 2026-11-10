package com.gamagerecruiters.marketplace.modules.auth.service;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

public interface TokenService {

    String generateAccessToken(String userId, String email, Role role);

    String generateRefreshToken();

    String hashToken(String rawToken);

    boolean validateToken(String token);

    String getUserIdFromToken(String token);

    Role getRoleFromToken(String token);

    String getEmailFromToken(String token);

    long getAccessTokenExpirationSeconds();

    long getRefreshTokenExpirationSeconds();
}
