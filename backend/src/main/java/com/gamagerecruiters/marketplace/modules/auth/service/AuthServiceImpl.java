package com.gamagerecruiters.marketplace.modules.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.AccountStatusException;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.CurrentUserResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.LoginRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.LogoutRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterRequest;
import com.gamagerecruiters.marketplace.modules.auth.dto.RegisterResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.UserSummaryResponse;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String GENERIC_BAD_CREDENTIALS_MSG = "Invalid email or password";

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    private final boolean requireEmailVerification;
    private final int maxFailedLogins;
    private final int lockDurationMinutes;
    private final int refreshReuseGraceSeconds;

    public AuthServiceImpl(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            @Value("${app.auth.require-email-verification:true}") boolean requireEmailVerification,
            @Value("${app.auth.max-failed-logins:5}") int maxFailedLogins,
            @Value("${app.auth.lock-duration-minutes:15}") int lockDurationMinutes,
            @Value("${app.auth.refresh-reuse-grace-seconds:10}") int refreshReuseGraceSeconds
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.requireEmailVerification = requireEmailVerification;
        this.maxFailedLogins = maxFailedLogins;
        this.lockDurationMinutes = lockDurationMinutes;
        this.refreshReuseGraceSeconds = refreshReuseGraceSeconds;
    }

    @Override
    public RegisterResponse register(RegisterRequest request) {
        // Validate role: self-registration is strictly allowed for CUSTOMER or PROVIDER only
        if (request.role() != Role.CUSTOMER && request.role() != Role.PROVIDER) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ROLE",
                    "Self-registration is only permitted for CUSTOMER or PROVIDER roles"
            );
        }

        String normalizedEmail = request.email().toLowerCase().trim();
        String normalizedPhone = request.phone().trim();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ResourceConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists");
        }

        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new ResourceConflictException("PHONE_ALREADY_EXISTS", "A user with this phone number already exists");
        }

        User user = new User();
        user.setName(request.name().trim());
        user.setEmail(normalizedEmail);
        user.setPhone(normalizedPhone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());

        // Issue 4: emailVerified is ALWAYS false until real email verification happens.
        // The dev bypass only controls status (ACTIVE vs PENDING_VERIFICATION).
        user.setEmailVerified(false);
        if (requireEmailVerification) {
            user.setStatus(UserStatus.PENDING_VERIFICATION);
        } else {
            user.setStatus(UserStatus.ACTIVE);
        }

        user.setConsentStatus(request.acceptedTerms());
        user.setConsentAt(Instant.now());
        user.setConsentPolicyVersion(request.policyVersion());
        user.setFailedLoginCount(0);
        user.setCreatedAt(Instant.now());

        User saved = userRepository.save(user);

        return new RegisterResponse(
                saved.getId(),
                saved.getEmail(),
                saved.getRole(),
                saved.getStatus()
        );
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        // TODO: Add rate limiting for login attempts
        String normalizedEmail = request.email().toLowerCase().trim();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException(GENERIC_BAD_CREDENTIALS_MSG));

        // Issue 1: Auto-unlock ONLY applies if status is LOCKED from failed attempts
        if (user.getStatus() == UserStatus.LOCKED) {
            if (user.getLockStartedAt() != null) {
                long minutesLocked = Duration.between(user.getLockStartedAt(), Instant.now()).toMinutes();
                if (minutesLocked >= lockDurationMinutes) {
                    user.setStatus(UserStatus.ACTIVE);
                    user.setFailedLoginCount(0);
                    user.setLockStartedAt(null);
                    userRepository.save(user);
                } else {
                    throw new AccountStatusException(
                            "ACCOUNT_LOCKED",
                            "Your account is temporarily locked due to multiple failed login attempts. Please try again later."
                    );
                }
            } else {
                throw new AccountStatusException(
                        "ACCOUNT_LOCKED",
                        "Your account is temporarily locked. Please contact support."
                );
            }
        }

        // Issue 1: Handle non-ACTIVE accounts (SUSPENDED, DEACTIVATED, PENDING_VERIFICATION)
        // If wrong password: do NOT change status or counter, and return the same generic 401.
        // If correct password: throw corresponding 403.
        if (user.getStatus() != UserStatus.ACTIVE) {
            boolean passwordMatch = passwordEncoder.matches(request.password(), user.getPasswordHash());
            if (!passwordMatch) {
                // Do not increment counter, do not change status, do not leak account state
                throw new BadCredentialsException(GENERIC_BAD_CREDENTIALS_MSG);
            }

            // Correct password, reveal appropriate account status error
            switch (user.getStatus()) {
                case PENDING_VERIFICATION -> throw new AccountStatusException(
                        "EMAIL_NOT_VERIFIED",
                        "Email verification is required before logging in."
                );
                case SUSPENDED -> throw new AccountStatusException(
                        "ACCOUNT_SUSPENDED",
                        "Your account has been suspended. Please contact support."
                );
                case DEACTIVATED -> throw new AccountStatusException(
                        "ACCOUNT_DEACTIVATED",
                        "Your account has been deactivated."
                );
                default -> throw new BadCredentialsException(GENERIC_BAD_CREDENTIALS_MSG);
            }
        }

        // User is ACTIVE: verify password and apply lockout counting strictly here
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int failedAttempts = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(failedAttempts);

            if (failedAttempts >= maxFailedLogins) {
                user.setStatus(UserStatus.LOCKED);
                user.setLockStartedAt(Instant.now());
            }

            userRepository.save(user);
            throw new BadCredentialsException(GENERIC_BAD_CREDENTIALS_MSG);
        }

        // Successful login for ACTIVE user: reset failed login tracking
        if (user.getFailedLoginCount() > 0 || user.getLockStartedAt() != null) {
            user.setFailedLoginCount(0);
            user.setLockStartedAt(null);
            userRepository.save(user);
        }

        return issueTokens(user);
    }

    @Override
    public AuthResponse refresh(RefreshTokenRequest request) {
        String tokenHash = tokenService.hashToken(request.refreshToken());
        Instant now = Instant.now();

        // Issue 3: Atomically claim and mark the token as used in a single conditional MongoDB update
        Optional<AuthToken> claimedTokenOpt = authTokenRepository.findAndConsumeValidToken(tokenHash, now);

        if (claimedTokenOpt.isEmpty()) {
            // Failed to claim: inspect existing token record to determine exact rejection reason
            Optional<AuthToken> existingOpt = authTokenRepository.findByTokenHash(tokenHash);

            if (existingOpt.isEmpty()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid refresh token");
            }

            AuthToken existing = existingOpt.get();
            if (existing.isUsed()) {
                // Token reuse detected — check grace window for concurrent refresh tolerance
                long elapsedSeconds = Duration.between(existing.getUsedAt(), now).getSeconds();
                if (elapsedSeconds <= refreshReuseGraceSeconds) {
                    // Within grace window: likely a concurrent request, not an attack.
                    // Return 409 Conflict without revoking user sessions.
                    throw new ApiException(HttpStatus.CONFLICT, "REFRESH_IN_PROGRESS",
                            "Refresh already in progress, retry with the latest token");
                }
                // Outside grace window: genuine token reuse attack.
                // Revoke all active refresh tokens for this user as a security measure.
                authTokenRepository.revokeAllActiveTokensByUserId(existing.getUserId());
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_REUSED", "Refresh token has already been used");
            }

            if (existing.isRevoked()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Refresh token has been revoked");
            }

            if (existing.isExpired()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Refresh token has expired");
            }

            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid refresh token");
        }

        AuthToken consumedToken = claimedTokenOpt.get();

        User user = userRepository.findById(consumedToken.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_ACTIVE", "User account is not active");
        }

        // Issue new token pair
        return issueTokens(user);
    }

    @Override
    public void logout(String currentUserId, LogoutRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            return;
        }

        String tokenHash = tokenService.hashToken(request.refreshToken());
        authTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            // Issue 5: Revoke only if the token belongs to the authenticated current user.
            // On mismatch, do not revoke and return neutrally to prevent token enumeration.
            if (currentUserId != null && currentUserId.equals(token.getUserId())) {
                if (token.getRevokedAt() == null) {
                    token.setRevokedAt(Instant.now());
                    authTokenRepository.save(token);
                }
            }
        });
    }

    @Override
    public CurrentUserResponse getCurrentUser(String currentUserId) {
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User profile not found"));

        return new CurrentUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.getCreatedAt()
        );
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = tokenService.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String rawRefreshToken = tokenService.generateRefreshToken();
        String refreshTokenHash = tokenService.hashToken(rawRefreshToken);

        Instant now = Instant.now();
        Instant refreshExpiry = now.plusSeconds(tokenService.getRefreshTokenExpirationSeconds());

        AuthToken authToken = new AuthToken(
                user.getId(),
                TokenType.REFRESH,
                refreshTokenHash,
                refreshExpiry,
                now
        );
        authTokenRepository.save(authToken);

        UserSummaryResponse summary = new UserSummaryResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus()
        );

        return AuthResponse.of(
                accessToken,
                rawRefreshToken,
                tokenService.getAccessTokenExpirationSeconds(),
                summary,
                user.isMustChangePassword()
        );
    }
}
