
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Unit tests for dispute payout hold preparation.
 *
 * <p>
 * These tests verify ownership, payment selection and idempotent
 * preparation. They do not represent an actual financial reservation.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DisputePayoutHoldServiceTest {

    private static final String DISPUTE_ID = "dispute-1";
    private static final String BOOKING_ID = "booking-1";
    private static final String PROVIDER_ID = "provider-1";
    private static final String CUSTOMER_ID = "customer-1";

    @Mock
    private DisputePayoutHoldRepository holdRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private BookingFacade bookingFacade;

    @Mock
    private EarningsLedgerRepository ledgerRepository;

    @Mock
    private EarningsBalanceCalculator balanceCalculator;

    @InjectMocks
    private DisputePayoutHoldService service;

    @BeforeEach
    void setUp() {
        // Each test explicitly configures only the collaborators it needs.
    }

    @Test
    void prepareHold_createsPendingHoldForValidBookingAndPayment() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        Payment payment = mockPayment(
                "payment-1", CUSTOMER_ID, "100.00", "10.00");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(payment));
        when(holdRepository.save(any(DisputePayoutHold.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DisputePayoutHold result = service.prepareHold(DISPUTE_ID, BOOKING_ID, PROVIDER_ID);

        assertEquals(DISPUTE_ID, result.getDisputeId());
        assertEquals(BOOKING_ID, result.getBookingId());
        assertEquals(PROVIDER_ID, result.getProviderId());
        assertEquals("payment-1", result.getPaymentId());
        assertEquals(0, new BigDecimal("90.00")
                .compareTo(result.getAmount()));
        assertEquals(DisputePayoutHold.HoldStatus.PENDING,
                result.getStatus());

        verify(holdRepository).save(any(DisputePayoutHold.class));
    }

    @Test
    void prepareHold_returnsExistingHoldForRepeatedDispute() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        DisputePayoutHold existing = new DisputePayoutHold(
                DISPUTE_ID, BOOKING_ID, "payment-1",
                PROVIDER_ID, new BigDecimal("90.00"), "LKR");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.of(existing));

        DisputePayoutHold result = service.prepareHold(DISPUTE_ID, BOOKING_ID, PROVIDER_ID);

        assertSame(existing, result);
        verify(holdRepository, never()).save(any());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void prepareHold_rejectsBookingProviderMismatch() {
        stubBooking("different-provider", CUSTOMER_ID);

        assertThrows(IllegalStateException.class,
                () -> service.prepareHold(
                        DISPUTE_ID, BOOKING_ID, PROVIDER_ID));

        verifyNoInteractions(paymentRepository, holdRepository);
    }

    @Test
    void prepareHold_rejectsPaymentCustomerMismatch() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        Payment payment = mockPayment(
                "payment-1", "different-customer", "100.00", "10.00");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.prepareHold(
                        DISPUTE_ID, BOOKING_ID, PROVIDER_ID));

        verify(holdRepository, never()).save(any());
    }

    @Test
    void prepareHold_rejectsMultipleEligiblePayments() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        Payment first = mockPayment(
                "payment-1", CUSTOMER_ID, "100.00", "10.00");
        Payment second = mockPayment(
                "payment-2", CUSTOMER_ID, "100.00", "10.00");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(first, second));

        assertThrows(IllegalStateException.class,
                () -> service.prepareHold(
                        DISPUTE_ID, BOOKING_ID, PROVIDER_ID));

        verify(holdRepository, never()).save(any());
    }

    @Test
    void prepareHold_rejectsMissingCommission() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        Payment payment = mockPayment(
                "payment-1", CUSTOMER_ID, "100.00", null);

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.prepareHold(
                        DISPUTE_ID, BOOKING_ID, PROVIDER_ID));

        verify(holdRepository, never()).save(any());
    }

    /**
     * A concurrent insert for the same dispute should reuse the matching hold.
     */
    @Test
    void prepareHold_duplicateKeyReturnsMatchingConcurrentHold() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        Payment payment = mockPayment(
                "payment-1", CUSTOMER_ID, "100.00", "10.00");

        DisputePayoutHold concurrentHold = new DisputePayoutHold(
                DISPUTE_ID, BOOKING_ID, "payment-1",
                PROVIDER_ID, new BigDecimal("90.00"), "LKR");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty(), Optional.of(concurrentHold));
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(payment));
        when(holdRepository.save(any(DisputePayoutHold.class)))
                .thenThrow(new DuplicateKeyException("Concurrent insert"));

        DisputePayoutHold result =
                service.prepareHold(DISPUTE_ID, BOOKING_ID, PROVIDER_ID);

        assertSame(concurrentHold, result);
        verify(holdRepository, times(2)).findByDisputeId(DISPUTE_ID);
        verify(holdRepository).save(any(DisputePayoutHold.class));
    }

    /**
     * A duplicate dispute ID must not be reused for another booking.
     */
    @Test
    void prepareHold_duplicateKeyRejectsMismatchedConcurrentHold() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        Payment payment = mockPayment(
                "payment-1", CUSTOMER_ID, "100.00", "10.00");

        DisputePayoutHold conflictingHold = new DisputePayoutHold(
                DISPUTE_ID, "different-booking", "payment-1",
                PROVIDER_ID, new BigDecimal("90.00"), "LKR");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty(), Optional.of(conflictingHold));
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(payment));
        when(holdRepository.save(any(DisputePayoutHold.class)))
                .thenThrow(new DuplicateKeyException("Concurrent insert"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.prepareHold(
                        DISPUTE_ID, BOOKING_ID, PROVIDER_ID));

        assertTrue(exception.getMessage().contains("different booking or provider"));
    }

    /**
     * An unresolved duplicate-key error must not be silently accepted.
     */
    @Test
    void prepareHold_duplicateKeyWithoutExistingHoldPropagates() {
        stubBooking(PROVIDER_ID, CUSTOMER_ID);

        Payment payment = mockPayment(
                "payment-1", CUSTOMER_ID, "100.00", "10.00");

        when(holdRepository.findByDisputeId(DISPUTE_ID))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(payment));
        when(holdRepository.save(any(DisputePayoutHold.class)))
                .thenThrow(new DuplicateKeyException("Concurrent insert"));

        assertThrows(DuplicateKeyException.class,
                () -> service.prepareHold(
                        DISPUTE_ID, BOOKING_ID, PROVIDER_ID));

        verify(holdRepository, times(2)).findByDisputeId(DISPUTE_ID);
    }

    /**
     * Configures the Booking module's read-only participant contract.
     */
    private void stubBooking(String providerId, String customerId) {
        BookingParticipants participants = new BookingParticipants(
                BOOKING_ID,
                "series-1",
                "listing-1",
                customerId,
                providerId,
                null,
                null,
                null);

        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(participants);
    }

    /**
     * Creates a mock payment with the fields used by hold preparation.
     */
    private Payment mockPayment(
            String paymentId,
            String customerId,
            String amount,
            String commission) {

        Payment payment = mock(Payment.class);

        when(payment.getId()).thenReturn(paymentId);
        lenient().when(payment.getBookingId()).thenReturn(BOOKING_ID);
        lenient().when(payment.getCustomerId()).thenReturn(customerId);
        when(payment.getAmount()).thenReturn(new BigDecimal(amount));
        when(payment.getCurrency()).thenReturn("LKR");
        when(payment.getStatus()).thenReturn(PaymentStatus.CAPTURED);

        if (commission != null) {
            lenient().when(payment.getCommissionAmount())
                    .thenReturn(new BigDecimal(commission));
        }

        return payment;
    }
}
