package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutRepository;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

class PayoutServiceTest {

    private PayoutRepository payoutRepository;
    private ProviderProfileRepository providerProfileRepository;
    private PayoutTransactionService transactionService;
    private PayoutService service;

    @BeforeEach
    void setUp() {
        payoutRepository = mock(PayoutRepository.class);
        providerProfileRepository = mock(ProviderProfileRepository.class);
        transactionService = mock(PayoutTransactionService.class);
        service = new PayoutService(
                payoutRepository,
                providerProfileRepository,
                transactionService);
    }

    @Test
    void requestPayoutUsesAuthenticatedUserIdInsteadOfProfileId() {
        when(providerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(profile("provider-profile-1")));
        when(transactionService.reserve(any(Payout.class))).thenAnswer(invocation -> {
            Payout requested = invocation.getArgument(0);
            ReflectionTestUtils.setField(requested, "id", "payout-1");
            return requested;
        });

        Payout result = service.requestPayout(
                "user-1", "request-key-1", new BigDecimal("5000.00"));

        assertEquals("user-1", result.getProviderId());
        assertEquals("LKR", result.getCurrency());
        verify(transactionService).reserve(any(Payout.class));
    }

    @Test
    void requestPayoutRejectsMissingProviderProfile() {
        when(providerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.requestPayout(
                        "user-1", "request-key-1", new BigDecimal("5000.00")));
    }

    @Test
    void duplicateMatchingRequestDelegatesToTransactionService() {
        when(providerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(profile("provider-profile-1")));

        Payout payout = new Payout(
                "request-key-1", "user-1",
                new BigDecimal("5000.00"), "LKR");
        when(transactionService.reserve(any(Payout.class))).thenReturn(payout);

        Payout result = service.requestPayout(
                "user-1", "request-key-1", new BigDecimal("5000.00"));

        assertEquals("request-key-1", result.getRequestKey());
    }

    @Test
    void conflictingRequestIsRejectedByTransactionService() {
        when(providerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(profile("provider-profile-1")));
        when(transactionService.reserve(any(Payout.class)))
                .thenThrow(new IllegalStateException(
                        "Payout request key belongs to different financial data"));

        assertThrows(IllegalStateException.class,
                () -> service.requestPayout(
                        "user-1", "request-key-1", new BigDecimal("6000.00")));
    }

    @Test
    void providerHistoryUsesAuthenticatedUserIdAndExistingPaginationConvention() {
        when(providerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(profile("provider-profile-1")));
        when(payoutRepository.findByProviderId(eq("user-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

        var result = service.getMyPayoutHistory("user-1", 0, 10);

        assertEquals(0, result.getTotalElements());
        verify(payoutRepository).findByProviderId(eq("user-1"), any(Pageable.class));
    }

    @Test
    void providerHistoryRejectsInvalidPagination() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getMyPayoutHistory("user-1", -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.getMyPayoutHistory("user-1", 0, 101));
    }

    @Test
    void providerCannotRetrieveAnotherProvidersPayout() {
        when(providerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(profile("provider-profile-1")));

        Payout payout = new Payout(
                "request-key-1", "provider-profile-2",
                new BigDecimal("5000.00"), "LKR");
        ReflectionTestUtils.setField(payout, "id", "payout-1");
        when(payoutRepository.findById("payout-1")).thenReturn(Optional.of(payout));

        assertThrows(IllegalStateException.class,
                () -> service.getMyPayout("user-1", "payout-1"));
    }

    @Test
    void paidAndFailedLifecycleDelegateWithTrustedAdminId() {
        Payout payout = new Payout(
                "request-key-1", "user-1",
                new BigDecimal("5000.00"), "LKR");

        when(transactionService.markPaid("payout-1", "admin-1", "BANK-REF-1"))
                .thenReturn(payout);
        when(transactionService.markFailed("payout-1", "admin-1", "rejected"))
                .thenReturn(payout);

        assertEquals(payout,
                service.markPaid("payout-1", "admin-1", "BANK-REF-1"));
        assertEquals(payout,
                service.markFailed("payout-1", "admin-1", "rejected"));
    }

    @Test
    void paidAndFailedRejectMissingTransferDetails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.markPaid("payout-1", "admin-1", " "));
        assertThrows(IllegalArgumentException.class,
                () -> service.markFailed("payout-1", "admin-1", " "));
    }

    @Test
    void payoutServicePropagatesFailClosedMaturityBlocker() {
        when(providerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(profile("provider-profile-1")));
        when(transactionService.reserve(any(Payout.class)))
                .thenThrow(new IllegalStateException(
                        "Payout blocked: approved 72-hour payout maturity contract is unresolved"));

        assertThrows(IllegalStateException.class,
                () -> service.requestPayout(
                        "user-1", "request-key-1", new BigDecimal("5000.00")));
    }

    private ProviderProfile profile(String id) {
        ProviderProfile profile = new ProviderProfile();
        profile.setId(id);
        profile.setUserId("user-1");
        return profile;
    }
}
