package com.gamagerecruiters.marketplace.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

class TokenServiceTest {

    private static final String TEST_SECRET = "super-secure-test-jwt-secret-key-that-is-at-least-256-bits-long-123456";
    private TokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        tokenService = new TokenServiceImpl(TEST_SECRET, 900, 604800, env);
        tokenService.init();
    }

    @Test
    @DisplayName("Should generate and validate JWT token successfully")
    void shouldGenerateAndValidateToken() {
        String token = tokenService.generateAccessToken("user-123", "user@marketplace.local", Role.CUSTOMER);

        assertNotNull(token);
        assertTrue(tokenService.validateToken(token));
        assertEquals("user-123", tokenService.getUserIdFromToken(token));
        assertEquals("user@marketplace.local", tokenService.getEmailFromToken(token));
        assertEquals(Role.CUSTOMER, tokenService.getRoleFromToken(token));
    }

    @Test
    @DisplayName("Should reject tampered or corrupted JWT token")
    void shouldRejectCorruptedToken() {
        String token = tokenService.generateAccessToken("user-123", "user@marketplace.local", Role.CUSTOMER);
        String tampered = token + "corrupted";

        assertFalse(tokenService.validateToken(tampered));
    }

    @Test
    @DisplayName("Should reject expired JWT token")
    void shouldRejectExpiredToken() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        // Expiration of -10 seconds
        TokenServiceImpl expiredTokenService = new TokenServiceImpl(TEST_SECRET, -10, 604800, env);
        expiredTokenService.init();

        String token = expiredTokenService.generateAccessToken("user-123", "user@marketplace.local", Role.CUSTOMER);
        assertFalse(tokenService.validateToken(token));
    }

    @Test
    @DisplayName("Should generate opaque refresh token and hash with SHA-256")
    void shouldGenerateAndHashRefreshToken() {
        String refreshToken = tokenService.generateRefreshToken();
        assertNotNull(refreshToken);
        assertFalse(refreshToken.isBlank());

        String hash1 = tokenService.hashToken(refreshToken);
        String hash2 = tokenService.hashToken(refreshToken);

        assertNotNull(hash1);
        assertEquals(64, hash1.length()); // SHA-256 hex length
        assertEquals(hash1, hash2); // Deterministic
    }
}
