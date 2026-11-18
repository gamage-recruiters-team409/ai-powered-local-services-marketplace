package com.gamagerecruiters.marketplace.modules.auth.repository;

import java.time.Instant;
import java.util.Optional;

import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;

public interface AuthTokenRepositoryCustom {

    /**
     * Atomically finds a valid, unexpired, unrevoked, and unused token of the specified type
     * and marks it as used with usedAt = now.
     *
     * @param tokenHash SHA-256 hash of the token
     * @param type The token type (e.g. REFRESH, EMAIL_VERIFICATION, PASSWORD_RESET)
     * @param now Current timestamp
     * @return The updated AuthToken document if successfully claimed, or empty if already used/expired/revoked/missing.
     */
    Optional<AuthToken> consumeValidToken(String tokenHash, TokenType type, Instant now);

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

    /**
     * Revokes all active tokens of a specific type for a user (e.g. all unrevoked REFRESH tokens).
     *
     * @param userId The user ID
     * @param type The token type
     */
    void revokeAllActiveTokensByUserIdAndType(String userId, TokenType type);

    /**
     * Revokes all unused, unrevoked tokens of a given userId and type
     * (used when issuing a new verification or reset token so only the newest link works).
     *
     * @param userId The user ID
     * @param type The token type
     */
    void revokeAllUnusedTokensByUserIdAndType(String userId, TokenType type);

    /**
     * Finds a valid, unexpired, unrevoked, and unused token of the specified type without consuming it.
     *
     * @param tokenHash SHA-256 hash of the token
     * @param type The token type
     * @param now Current timestamp
     * @return Optional containing the AuthToken if valid, or empty if used/expired/revoked/missing.
     */
    Optional<AuthToken> findValidToken(String tokenHash, TokenType type, Instant now);
}
