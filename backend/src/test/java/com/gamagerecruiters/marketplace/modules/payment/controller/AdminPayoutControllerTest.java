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
import com.gamagerecruiters.marketplace.modules.payment.dto.ConfirmPayoutRequest;
import com.gamagerecruiters.marketplace.modules.payment.dto.FailPayoutRequest;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.service.PayoutService;

class AdminPayoutControllerTest {

    private static final String ADMIN_ID = "finance-admin-1";

    private PayoutService payoutService;
    private AdminPayoutController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        payoutService = mock(PayoutService.class);
        controller = new AdminPayoutController(payoutService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getPayoutsReturnsOk() {
        var page = new PageImpl<>(
                List.of(payout("payout-1", "provider-profile-1")),
                PageRequest.of(0, 10),
                1);
        when(payoutService.getAdminPayoutHistory(0, 10)).thenReturn(page);

        var response = controller.getPayouts(0, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().content().size());
    }

    @Test
    void markProcessingUsesAuthenticatedAdminId() {
        authenticateFinanceAdmin();
        Payout payout = payout("payout-1", "provider-profile-1");
        payout.markProcessing(ADMIN_ID);
        when(payoutService.markProcessing("payout-1", ADMIN_ID)).thenReturn(payout);

        var response = controller.markProcessing("payout-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(payoutService).markProcessing("payout-1", ADMIN_ID);
    }

    @Test
    void markPaidRequiresBankTransferReference() {
        authenticateFinanceAdmin();
        Payout payout = payout("payout-1", "provider-profile-1");
        payout.markProcessing(ADMIN_ID);
        payout.markPaid(ADMIN_ID, "BANK-REF-1");
        when(payoutService.markPaid("payout-1", ADMIN_ID, "BANK-REF-1"))
                .thenReturn(payout);

        var response = controller.markPaid(
                "payout-1",
                new ConfirmPayoutRequest("BANK-REF-1"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("BANK-REF-1", response.getBody().transferReference());
        verify(payoutService).markPaid("payout-1", ADMIN_ID, "BANK-REF-1");
    }

    @Test
    void markFailedUsesExplicitReason() {
        authenticateFinanceAdmin();
        Payout payout = payout("payout-1", "provider-profile-1");
        payout.markProcessing(ADMIN_ID);
        when(payoutService.markFailed("payout-1", ADMIN_ID, "bank rejected"))
                .thenReturn(payout);

        var response = controller.markFailed(
                "payout-1",
                new FailPayoutRequest("bank rejected"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(payoutService).markFailed("payout-1", ADMIN_ID, "bank rejected");
    }

    @Test
    void adminMutationRejectsMissingAuthentication() {
        ApiException exception = assertThrows(ApiException.class,
                () -> controller.markProcessing("payout-1"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verifyNoInteractions(payoutService);
    }

    private void authenticateFinanceAdmin() {
        UserPrincipal principal = new UserPrincipal(
                ADMIN_ID,
                "finance@example.com",
                Role.FINANCE_ADMIN);
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
