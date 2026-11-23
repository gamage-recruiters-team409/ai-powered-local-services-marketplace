package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutAllocation;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutAllocationRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutRepository;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

/**
 * Verifies financial provider identity across payout creation, booking,
 * allocation, and ledger boundaries when profile and user IDs differ.
 */
class PayoutProviderIdentityIntegrationTest {

    @Test
    void payoutUsesAuthenticatedUserIdAcrossFinancialBoundaries() {
        String userId = "user-1";
        String profileId = "provider-profile-1";
        BigDecimal amount = new BigDecimal("5000.00");

        PayoutRepository payouts = mock(PayoutRepository.class);
        PayoutAllocationRepository allocations = mock(PayoutAllocationRepository.class);
        EarningsLedgerRepository ledger = mock(EarningsLedgerRepository.class);
        EarningsLedgerService ledgerService = mock(EarningsLedgerService.class);
        BookingPayoutAllocationCalculator calculator =
                mock(BookingPayoutAllocationCalculator.class);
        BookingFinancialSnapshotService snapshots =
                mock(BookingFinancialSnapshotService.class);
        DisputePayoutHoldRepository holds = mock(DisputePayoutHoldRepository.class);
        BookingFacade bookings = mock(BookingFacade.class);
        CompletionLookup completion = mock(CompletionLookup.class);
        ProviderProfileRepository profiles = mock(ProviderProfileRepository.class);

        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());

        ProviderProfile profile = new ProviderProfile();
        profile.setId(profileId);
        profile.setUserId(userId);
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));

        when(payouts.findByRequestKey("request-1")).thenReturn(Optional.empty());
        when(payouts.save(any(Payout.class))).thenAnswer(invocation -> {
            Payout payout = invocation.getArgument(0);
            ReflectionTestUtils.setField(payout, "id", "payout-1");
            return payout;
        });

        EarningsLedgerEntry earning = new EarningsLedgerEntry();
        earning.setEventKey("earning-pending:payment-1");
        earning.setProviderId(userId);
        earning.setPaymentId("payment-1");
        earning.setBookingId("booking-1");
        earning.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        earning.setAmount(amount);
        earning.setCurrency("LKR");
        earning.setSequenceNumber(1L);
        earning.setCreatedAt(Instant.parse("2026-11-10T10:00:00Z"));

        when(ledger.findByProviderIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(earning));

        when(snapshots.getSnapshot("booking-1")).thenReturn(
                new BookingFinancialSnapshotService.FinancialSnapshot(
                        "payment-1", "booking-1", "customer-1", userId,
                        "LKR", PaymentStatus.CAPTURED,
                        new BigDecimal("6000.00"),
                        new BigDecimal("1000.00"),
                        amount, BigDecimal.ZERO));

        when(bookings.getBookingParticipants("booking-1")).thenReturn(
                new BookingParticipants(
                        "booking-1", null, "listing-1", "customer-1",
                        userId, BookingStatus.COMPLETED,
                        Instant.parse("2026-11-10T10:00:00Z"),
                        Instant.parse("2026-11-10T11:00:00Z")));

        when(completion.findConfirmedAt("booking-1"))
                .thenReturn(Optional.of(Instant.parse("2026-11-10T11:00:00Z")));
        when(holds.findByBookingId("booking-1")).thenReturn(List.of());
        when(calculator.calculateAvailable(any())).thenReturn(amount);

        PayoutTransactionService transactions = new PayoutTransactionService(
                payouts, allocations, ledger, ledgerService, calculator,
                snapshots, holds, bookings, completion, transactionManager);

        PayoutService service = new PayoutService(payouts, profiles, transactions);

        Payout result = service.requestPayout(userId, "request-1", amount);

        assertNotEquals(profileId, userId);
        assertEquals(userId, result.getProviderId());

        verify(profiles).findByUserId(userId);
        verify(ledger).findByProviderIdOrderByCreatedAtDesc(userId);
        verify(bookings).getBookingParticipants("booking-1");

        ArgumentCaptor<PayoutAllocation> allocationCaptor =
                ArgumentCaptor.forClass(PayoutAllocation.class);
        verify(allocations).save(allocationCaptor.capture());

        assertEquals(userId, allocationCaptor.getValue().getProviderId());
        assertEquals("booking-1", allocationCaptor.getValue().getBookingId());

        ArgumentCaptor<EarningsLedgerEntry> eventCaptor =
                ArgumentCaptor.forClass(EarningsLedgerEntry.class);
        verify(ledgerService).appendEvent(eventCaptor.capture());

        assertEquals(userId, eventCaptor.getValue().getProviderId());
        assertEquals(EarningsLedgerEventType.PAYOUT_RESERVE,
                eventCaptor.getValue().getEventType());
    }
}
