package com.gamagerecruiters.marketplace.modules.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

@Service
public class TokenServiceImpl implements TokenService {

    private final String rawSecret;
    private final long accessTokenExpirationSeconds;
    private final long refreshTokenExpirationSeconds;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    private SecretKey signingKey;

    public TokenServiceImpl(
            @Value("${app.jwt.secret:}") String rawSecret,
            @Value("${app.jwt.access-token-expiration-seconds:900}") long accessTokenExpirationSeconds,
            @Value("${app.jwt.refresh-token-expiration-seconds:604800}") long refreshTokenExpirationSeconds,
            Environment environment
    ) {
        this.rawSecret = rawSecret;
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        boolean isTestProfile = Arrays.asList(environment.getActiveProfiles()).contains("test");

        if (rawSecret == null || rawSecret.trim().getBytes(StandardCharsets.UTF_8).length < 32) {
            if (isTestProfile) {
                String fallbackSecret = "test-secret-key-must-be-at-least-256-bits-long-for-testing-purpose-only";
                this.signingKey = Keys.hmacShaKeyFor(fallbackSecret.getBytes(StandardCharsets.UTF_8));
                return;
            }
            throw new IllegalStateException(
                    "JWT_SECRET environment variable is missing or shorter than 256 bits (32 bytes). " +
                    "Please define a secure JWT_SECRET in your environment or backend/.env file."
            );
        }

        this.signingKey = Keys.hmacShaKeyFor(rawSecret.trim().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String generateAccessToken(String userId, String email, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenExpirationSeconds);

        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role.name())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public String generateRefreshToken() {
        byte[] randomBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    @Override
    public String hashToken(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration() != null && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public String getUserIdFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    @Override
    public Role getRoleFromToken(String token) {
        String roleStr = parseClaims(token).get("role", String.class);
        return Role.valueOf(roleStr);
    }

    @Override
    public String getEmailFromToken(String token) {
        return parseClaims(token).get("email", String.class);
    }

    @Override
    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    @Override
    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationSeconds;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
