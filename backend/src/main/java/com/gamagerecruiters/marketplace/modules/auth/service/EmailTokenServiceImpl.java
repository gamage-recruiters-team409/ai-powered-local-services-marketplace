package com.gamagerecruiters.marketplace.modules.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailResult;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailSender;
import com.gamagerecruiters.marketplace.modules.notification.service.EmailType;

@Service
public class EmailTokenServiceImpl implements EmailTokenService {

    private static final Logger log = LoggerFactory.getLogger(EmailTokenServiceImpl.class);

    private static final Pattern PASSWORD_POLICY = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");
    private static final String INVALID_TOKEN_MSG = "Invalid or expired token";

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

    private final int emailVerificationExpiryMinutes;
    private final int passwordResetExpiryMinutes;
    private final int resendVerificationWaitSeconds;
    private final String frontendBaseUrl;

    public EmailTokenServiceImpl(
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            TokenService tokenService,
            PasswordEncoder passwordEncoder,
            EmailSender emailSender,
            @Value("${app.auth.email-verification-expiry-minutes:1440}") int emailVerificationExpiryMinutes,
            @Value("${app.auth.password-reset-expiry-minutes:30}") int passwordResetExpiryMinutes,
            @Value("${app.auth.resend-verification-wait-seconds:60}") int resendVerificationWaitSeconds,
            @Value("${app.frontend.base-url:http://localhost:5173}") String frontendBaseUrl
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.emailVerificationExpiryMinutes = emailVerificationExpiryMinutes;
        this.passwordResetExpiryMinutes = passwordResetExpiryMinutes;
        this.resendVerificationWaitSeconds = resendVerificationWaitSeconds;
        this.frontendBaseUrl = cleanBaseUrl(frontendBaseUrl);
    }

    @Override
    public void sendVerificationEmail(User user) {
        if (user == null || user.getId() == null) {
            return;
        }

        authTokenRepository.revokeAllUnusedTokensByUserIdAndType(user.getId(), TokenType.EMAIL_VERIFICATION);

        String rawToken = tokenService.generateOpaqueToken();
        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(emailVerificationExpiryMinutes));

        AuthToken authToken = new AuthToken(user.getId(), TokenType.EMAIL_VERIFICATION, tokenHash, expiresAt, now);
        authTokenRepository.save(authToken);

        String verificationLink = frontendBaseUrl + "/verify-email?token=" + rawToken;
        Map<String, Object> data = Map.of(
                "name", user.getName() != null ? user.getName() : "",
                "verificationLink", verificationLink,
                "expiresInMinutes", Integer.valueOf(emailVerificationExpiryMinutes)
        );

        try {
            EmailResult result = emailSender.sendEmail(EmailType.ACCOUNT_VERIFICATION, user.getEmail(), data);
            if (result == null || !result.success()) {
                log.warn("Failed to send account verification email for userId={}", user.getId());
            }
        } catch (Exception ex) {
            log.warn("Exception while sending account verification email for userId={}", user.getId());
        }
    }

    @Override
    public void verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG);
        }

        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();

        AuthToken token = authTokenRepository.consumeValidToken(tokenHash, TokenType.EMAIL_VERIFICATION, now)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG));

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG));

        user.setEmailVerified(true);
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        // Never change SUSPENDED, LOCKED, or DEACTIVATED status

        userRepository.save(user);
    }

    @Override
    public void resendVerification(String email) {
        String normalizedEmail = email != null ? email.trim().toLowerCase(Locale.ROOT) : "";
        if (normalizedEmail.isEmpty()) {
            return;
        }

        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        if (user.getStatus() != UserStatus.PENDING_VERIFICATION || user.isEmailVerified()) {
            return;
        }

        Optional<AuthToken> latestTokenOpt = authTokenRepository.findFirstByUserIdAndTypeOrderByCreatedAtDesc(
                user.getId(), TokenType.EMAIL_VERIFICATION
        );

        if (latestTokenOpt.isPresent()) {
            Instant createdAt = latestTokenOpt.get().getCreatedAt();
            if (createdAt != null && Duration.between(createdAt, Instant.now()).getSeconds() < resendVerificationWaitSeconds) {
                return; // Resend requested too soon; do nothing silently
            }
        }

        sendVerificationEmail(user);
    }

    @Override
    public void forgotPassword(String email) {
        String normalizedEmail = email != null ? email.trim().toLowerCase(Locale.ROOT) : "";
        if (normalizedEmail.isEmpty()) {
            return;
        }

        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        // Only ACTIVE or LOCKED users are eligible. Do NOT send to SUSPENDED, DEACTIVATED, PENDING_VERIFICATION.
        if (user.getStatus() != UserStatus.ACTIVE && user.getStatus() != UserStatus.LOCKED) {
            return;
        }

        authTokenRepository.revokeAllUnusedTokensByUserIdAndType(user.getId(), TokenType.PASSWORD_RESET);

        String rawToken = tokenService.generateOpaqueToken();
        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(passwordResetExpiryMinutes));

        AuthToken authToken = new AuthToken(user.getId(), TokenType.PASSWORD_RESET, tokenHash, expiresAt, now);
        authTokenRepository.save(authToken);

        String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
        Map<String, Object> data = Map.of(
                "name", user.getName() != null ? user.getName() : "",
                "resetLink", resetLink,
                "expiresInMinutes", Integer.valueOf(passwordResetExpiryMinutes)
        );

        try {
            EmailResult result = emailSender.sendEmail(EmailType.PASSWORD_RESET, user.getEmail(), data);
            if (result == null || !result.success()) {
                log.warn("Failed to send password reset email for userId={}", user.getId());
            }
        } catch (Exception ex) {
            log.warn("Exception while sending password reset email for userId={}", user.getId());
        }
    }

    @Override
    public void resetPassword(String rawToken, String newPassword) {
        if (newPassword == null || !PASSWORD_POLICY.matcher(newPassword).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "WEAK_PASSWORD",
                    "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, and one digit"
            );
        }

        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG);
        }

        String tokenHash = tokenService.hashToken(rawToken);
        Instant now = Instant.now();

        AuthToken token = authTokenRepository.findByTokenHash(tokenHash)
                .filter(t -> t.getType() == TokenType.PASSWORD_RESET && t.isValid())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG));

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG));

        if (user.getStatus() != UserStatus.ACTIVE && user.getStatus() != UserStatus.LOCKED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG);
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_PASSWORD", "New password cannot be the same as the current password");
        }

        authTokenRepository.consumeValidToken(tokenHash, TokenType.PASSWORD_RESET, now)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN", INVALID_TOKEN_MSG));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setFailedLoginCount(0);
        user.setLockStartedAt(null);
        if (user.getStatus() == UserStatus.LOCKED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);

        // Revoke all refresh tokens so previous sessions die, and invalidate other unused password reset tokens
        authTokenRepository.revokeAllActiveTokensByUserIdAndType(user.getId(), TokenType.REFRESH);
        authTokenRepository.revokeAllUnusedTokensByUserIdAndType(user.getId(), TokenType.PASSWORD_RESET);
    }

    private static String cleanBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:5173";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
