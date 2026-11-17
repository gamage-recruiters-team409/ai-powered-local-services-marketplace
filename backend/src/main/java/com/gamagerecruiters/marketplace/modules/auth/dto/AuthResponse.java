package com.gamagerecruiters.marketplace.modules.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserSummaryResponse user,
        boolean mustChangePassword
) {
    public static AuthResponse of(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UserSummaryResponse user,
            boolean mustChangePassword
    ) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, user, mustChangePassword);
    }

    public static AuthResponse of(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UserSummaryResponse user
    ) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, user, false);
    }
}
