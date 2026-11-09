package com.gamagerecruiters.marketplace.modules.auth.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AuthResponse;
import com.gamagerecruiters.marketplace.modules.auth.dto.RefreshTokenRequest;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.service.AuthServiceImpl;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;

@ExtendWith(MockitoExtension.class)
class RefreshConcurrencyUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthTokenRepository authTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                userRepository,
                authTokenRepository,
                passwordEncoder,
                tokenService,
                false,
                5,
                15,
                10 // 10 second grace window
        );
    }

    @Test
    @DisplayName("Issue D: 10 parallel threads -> exactly 1 succeeds, 9 fail with 409 REFRESH_IN_PROGRESS within grace window")
    void test10ParallelRefreshesWithGraceWindow() throws InterruptedException {
        String rawToken = "concurrent-raw-token";
        String tokenHash = "hash-token-123";

        User user = new User();
        user.setId("u-concurrency");
        user.setEmail("user@marketplace.local");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);

        AuthToken consumedToken = new AuthToken("u-concurrency", TokenType.REFRESH, tokenHash, Instant.now().plusSeconds(3600), Instant.now());
        consumedToken.setUsedAt(Instant.now());

        when(tokenService.hashToken(rawToken)).thenReturn(tokenHash);
        when(userRepository.findById("u-concurrency")).thenReturn(Optional.of(user));
        when(tokenService.generateAccessToken("u-concurrency", "user@marketplace.local", Role.CUSTOMER)).thenReturn("new-access-token");
        when(tokenService.generateRefreshToken()).thenReturn("new-refresh-token");
        when(tokenService.hashToken("new-refresh-token")).thenReturn("new-refresh-hash");
        when(tokenService.getAccessTokenExpirationSeconds()).thenReturn(900L);

        // Atomic consumption simulation: exactly 1 thread gets the token, the rest get empty
        AtomicBoolean consumedFirstTime = new AtomicBoolean(false);
        when(authTokenRepository.findAndConsumeValidToken(eq(tokenHash), any(Instant.class))).thenAnswer(inv -> {
            if (consumedFirstTime.compareAndSet(false, true)) {
                return Optional.of(consumedToken);
            }
            return Optional.empty();
        });

        // Other threads find the already-consumed token with usedAt set to now (0 elapsed seconds <= 10s grace)
        when(authTokenRepository.findByTokenHash(tokenHash)).thenAnswer(inv -> Optional.of(consumedToken));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    AuthResponse res = authService.refresh(new RefreshTokenRequest(rawToken));
                    if (res != null) {
                        successCount.incrementAndGet();
                    }
                } catch (ApiException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT && "REFRESH_IN_PROGRESS".equals(e.getErrorCode())) {
                        conflictCount.incrementAndGet();
                    } else {
                        errors.add(e);
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(0, errors.size(), "Errors: " + errors);
        assertEquals(1, successCount.get(), "Exactly 1 thread must succeed");
        assertEquals(9, conflictCount.get(), "9 threads must receive 409 Conflict");
        verify(authTokenRepository, never()).revokeAllActiveTokensByUserId(any());
    }
}
