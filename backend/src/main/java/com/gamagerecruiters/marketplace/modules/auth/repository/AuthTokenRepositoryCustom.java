package com.gamagerecruiters.marketplace.modules.auth.repository;

import java.time.Instant;
import java.util.Optional;

import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;

public interface AuthTokenRepositoryCustom {

    /**
     * Atomically finds a valid, unexpired, unrevoked, and unused refresh token
     * and marks it as used with usedAt = now.
     *
     * @param tokenHash SHA-256 hash of the refresh token
     * @param now Current timestamp
     * @return The updated AuthToken document if successfully claimed, or empty if already used/expired/revoked/missing.
     */
    Optional<AuthToken> findAndConsumeValidToken(String tokenHash, Instant now);

    /**
     * Revokes all active refresh tokens for a user (used on reuse detection).
     *
     * @param userId The user ID
     */
    void revokeAllActiveTokensByUserId(String userId);
}
