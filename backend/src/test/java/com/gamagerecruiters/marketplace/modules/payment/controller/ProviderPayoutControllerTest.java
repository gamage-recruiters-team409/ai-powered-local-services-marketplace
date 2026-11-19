package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.payment.dto.CreatePayoutRequest;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.service.PayoutService;

class ProviderPayoutControllerTest {

    private static final String USER_ID = "user-provider-1";

    private PayoutService payoutService;
    private ProviderPayoutController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        payoutService = mock(PayoutService.class);
        controller = new ProviderPayoutController(payoutService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestPayoutReturnsCreatedAndUsesAuthenticatedUserId() {
        authenticate(Role.PROVIDER);
        Payout payout = payout("payout-1", "provider-profile-1");
        when(payoutService.requestPayout(
                USER_ID, "request-1", new BigDecimal("5000.00")))
                .thenReturn(payout);

        var response = controller.requestPayout(
                new CreatePayoutRequest("request-1", new BigDecimal("5000.00")));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("payout-1", response.getBody().id());
        verify(payoutService).requestPayout(
                USER_ID, "request-1", new BigDecimal("5000.00"));
    }

    @Test
    void getMyPayoutHistoryReturnsOkAndProviderView() {
        authenticate(Role.PROVIDER);
        var page = new PageImpl<>(
                List.of(payout("payout-1", "provider-profile-1")),
                PageRequest.of(0, 10),
                1);
        when(payoutService.getMyPayoutHistory(USER_ID, 0, 10)).thenReturn(page);

        var response = controller.getMyPayouts(0, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().content().size());
        assertEquals("payout-1", response.getBody().content().get(0).id());
    }

    @Test
    void getMyPayoutRejectsMissingAuthentication() {
        ApiException exception = assertThrows(ApiException.class,
                () -> controller.getMyPayout("payout-1"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verifyNoInteractions(payoutService);
    }

    private void authenticate(Role role) {
        UserPrincipal principal = new UserPrincipal(
                USER_ID, "provider@example.com", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()));
    }

    private Payout payout(String id, String providerId) {
        Payout payout = new Payout(
                "request-1",
                providerId,
                new BigDecimal("5000.00"),
                "LKR");
        ReflectionTestUtils.setField(payout, "id", id);
        ReflectionTestUtils.setField(payout, "createdAt",
                Instant.parse("2026-11-19T10:00:00Z"));
        ReflectionTestUtils.setField(payout, "updatedAt",
                Instant.parse("2026-11-19T10:00:00Z"));
        return payout;
    }
}
