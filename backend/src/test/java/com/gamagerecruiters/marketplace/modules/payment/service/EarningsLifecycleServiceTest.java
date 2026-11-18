
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Unit tests for the proposed pending earnings lifecycle.
 *
 * <p>
 * Dependencies are mocked so these tests do not access MongoDB,
 * change booking records, or communicate with a payment gateway.
 * </p>
 */
class EarningsLifecycleServiceTest {

    private PaymentPersistenceService paymentPersistenceService;
    private BookingFacade bookingFacade;
    private CompletionLookup completionLookup;
    private EarningsLedgerService earningsLedgerService;
    private EarningsLifecycleService service;

    @BeforeEach
    void setUp() {
        paymentPersistenceService = mock(PaymentPersistenceService.class);
        bookingFacade = mock(BookingFacade.class);
        completionLookup = mock(CompletionLookup.class);
        lenient().when(completionLookup.findConfirmedAt("booking-001"))
                        .thenReturn(Optional.of(
                                        java.time.Instant.parse("2026-11-18T10:00:00Z")));
        earningsLedgerService = mock(EarningsLedgerService.class);

        service = new EarningsLifecycleService(
                paymentPersistenceService,
                bookingFacade,
                completionLookup,
                earningsLedgerService);
    }

    @Test
    void capturedPaymentCreatesPendingEarningsEvent() {
        Payment payment = validCapturedPayment();
        BookingParticipants participants = validParticipants();

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(participants);

        when(earningsLedgerService.appendEvent(
                any(EarningsLedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EarningsLedgerEntry result = service.recordPendingEarnings("payment-001");

        assertEquals(
                EarningsLedgerEventType.EARNING_PENDING,
                result.getEventType());

        assertEquals("payment-001", result.getPaymentId());
        assertEquals("booking-001", result.getBookingId());
        assertEquals("provider-001", result.getProviderId());
        assertEquals(new BigDecimal("850.00"), result.getAmount());
        assertEquals("LKR", result.getCurrency());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getEventKey());

        verify(earningsLedgerService)
                .appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void rejectsCapturedPaymentWhenBookingIsIncomplete() {
        Payment payment = validCapturedPayment();

        BookingParticipants participants = new BookingParticipants(
                "booking-001",
                null,
                "listing-001",
                "customer-001",
                "provider-001",
                BookingStatus.IN_PROGRESS,
                null,
                null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(participants);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        assertEquals("Pending earnings require a COMPLETED booking",
                exception.getMessage());

        verifyNoInteractions(earningsLedgerService);
    }

    @Test
    void rejectsCompletedBookingWithoutVerifiedConfirmation() {
        Payment payment = validCapturedPayment();

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(validParticipants());

        // A completed booking status alone is insufficient without WP08 evidence.
        when(completionLookup.findConfirmedAt("booking-001"))
                .thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        assertEquals("Pending earnings require verified service completion",
                exception.getMessage());

        verifyNoInteractions(earningsLedgerService);
    }

    @Test
    void rejectsCapturedPaymentWhenBookingIsCancelled() {
        Payment payment = validCapturedPayment();

        BookingParticipants participants = new BookingParticipants(
                "booking-001",
                null,
                "listing-001",
                "customer-001",
                "provider-001",
                BookingStatus.CANCELLED_BY_CUSTOMER,
                null,
                null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(participants);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        assertEquals("Pending earnings require a COMPLETED booking",
                exception.getMessage());

        verifyNoInteractions(earningsLedgerService);
    }

    @Test
    void rejectsPaymentThatIsNotCaptured() {
        Payment payment = validCapturedPayment();
        payment.setStatus(PaymentStatus.INITIATED);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        verifyNoInteractions(bookingFacade, earningsLedgerService);
    }

    @Test
    void rejectsMissingPayment() {
        when(paymentPersistenceService.findPaymentById("missing-payment"))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("missing-payment"));

        verifyNoInteractions(bookingFacade, earningsLedgerService);
    }

    @Test
    void rejectsPersistedPaymentWithMismatchedIdentifier() {
        Payment payment = validCapturedPayment();
        payment.setId("different-payment");

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        // A mismatched persisted payment must never generate earnings.
        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        verifyNoInteractions(bookingFacade, earningsLedgerService);
    }

    @Test
    void rejectsCommissionGreaterThanPaymentAmount() {
        Payment payment = validCapturedPayment();
        payment.setCommissionAmount(new BigDecimal("1200.00"));

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        verifyNoInteractions(bookingFacade, earningsLedgerService);
    }

    @Test
    void rejectsMissingProviderIdentifier() {
        Payment payment = validCapturedPayment();

        BookingParticipants participants = new BookingParticipants(
                "booking-001",
                null,
                "listing-001",
                "customer-001",
                null,
                BookingStatus.CONFIRMED,
                null,
                null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(participants);

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        verifyNoInteractions(earningsLedgerService);
    }

    @Test
    void rejectsPaymentWithMismatchedBookingCustomer() {
        Payment payment = validCapturedPayment();
        payment.setCustomerId("different-customer");

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(validParticipants());

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        // Invalid ownership must not produce a financial ledger event.
        verifyNoInteractions(earningsLedgerService);
    }

    @Test
    void rejectsMismatchedBookingIdentifier() {
        Payment payment = validCapturedPayment();

        BookingParticipants participants = new BookingParticipants(
                "different-booking",
                null,
                "listing-001",
                "customer-001",
                "provider-001",
                BookingStatus.COMPLETED,
                null,
                null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(participants);

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        // Mismatched booking data must not create earnings.
        verifyNoInteractions(earningsLedgerService);
    }

    @Test
    void rejectsPaymentWithMissingCustomerId() {
        Payment payment = validCapturedPayment();
        payment.setCustomerId(null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(validParticipants());

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        // A payment without a verified customer must not create earnings.
        verifyNoInteractions(earningsLedgerService);
    }

    @Test
    void rejectsCommissionWithExcessiveDecimalPrecision() {
        Payment payment = validCapturedPayment();
        payment.setCommissionAmount(new BigDecimal("150.123"));

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        // Invalid financial precision must be rejected before booking lookup.
        verifyNoInteractions(bookingFacade, earningsLedgerService);
    }

    @Test
    void rejectsNegativeCommissionAmount() {
        Payment payment = validCapturedPayment();
        payment.setCommissionAmount(new BigDecimal("-50.00"));

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        // A negative commission snapshot must never generate earnings.
        verifyNoInteractions(bookingFacade, earningsLedgerService);
    }

    @Test
    void rejectsZeroPaymentAmount() {
        Payment payment = validCapturedPayment();
        payment.setAmount(new BigDecimal("0.00"));

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        // A zero-value payment must never generate provider earnings.
        verifyNoInteractions(bookingFacade, earningsLedgerService);
    }

    @Test
    void propagatesLedgerPersistenceFailure() {
        Payment payment = validCapturedPayment();

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(validParticipants());

        // Simulate a failure while recording the pending earnings event.
        when(earningsLedgerService.appendEvent(
                any(EarningsLedgerEntry.class)))
                .thenThrow(new IllegalStateException(
                        "Ledger persistence failed"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        assertEquals("Ledger persistence failed", exception.getMessage());

        verify(earningsLedgerService)
                .appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void repeatedProcessingUsesSameEventKey() {
        Payment payment = validCapturedPayment();

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));

        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(validParticipants());

        when(earningsLedgerService.appendEvent(
                any(EarningsLedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EarningsLedgerEntry first = service.recordPendingEarnings("payment-001");

        EarningsLedgerEntry second = service.recordPendingEarnings("payment-001");

        assertEquals(first.getEventKey(), second.getEventKey());
        assertEquals(first.getAmount(), second.getAmount());

        // Ledger persistence enforces actual duplicate-event protection.
        verify(earningsLedgerService, times(2))
                .appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void recoversExistingPendingEarningsAfterBookingBecomesDisputed() {
        Payment payment = validCapturedPayment();

        BookingParticipants disputed = new BookingParticipants(
                "booking-001", null, "listing-001", "customer-001",
                "provider-001", BookingStatus.DISPUTED, null, null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));
        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(disputed);

        EarningsLedgerEntry committed = new EarningsLedgerEntry();
        committed.setEventKey("earning-pending:payment-001");
        committed.setPaymentId("payment-001");
        committed.setBookingId("booking-001");
        committed.setProviderId("provider-001");
        committed.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        committed.setAmount(new BigDecimal("850.00"));
        committed.setCurrency("LKR");

        when(earningsLedgerService.findMatchingCommittedEvent(any(EarningsLedgerEntry.class)))
                .thenReturn(Optional.of(committed));

        EarningsLedgerEntry result = service.recordPendingEarnings("payment-001");

        assertSame(committed, result);
        verify(earningsLedgerService).findMatchingCommittedEvent(any(EarningsLedgerEntry.class));
        verify(earningsLedgerService, never()).appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void rejectsDisputedBookingWithoutExistingPendingEarnings() {
        Payment payment = validCapturedPayment();

        BookingParticipants disputed = new BookingParticipants(
                "booking-001", null, "listing-001", "customer-001",
                "provider-001", BookingStatus.DISPUTED, null, null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));
        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(disputed);
        when(earningsLedgerService.findMatchingCommittedEvent(any(EarningsLedgerEntry.class)))
                .thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        assertEquals(
                "Disputed booking has no previously committed pending earnings event",
                exception.getMessage());

        verify(earningsLedgerService, never()).appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void rejectsDisputedBookingWhenCommittedFinancialDataConflicts() {
        Payment payment = validCapturedPayment();

        BookingParticipants disputed = new BookingParticipants(
                "booking-001", null, "listing-001", "customer-001",
                "provider-001", BookingStatus.DISPUTED, null, null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));
        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(disputed);

        when(earningsLedgerService.findMatchingCommittedEvent(any(EarningsLedgerEntry.class)))
                .thenThrow(new IllegalStateException(
                        "Ledger event key already exists with different financial data"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        assertEquals(
                "Ledger event key already exists with different financial data",
                exception.getMessage());

        verify(earningsLedgerService, never()).appendEvent(any(EarningsLedgerEntry.class));
    }

    @Test
    void rejectsDisputedBookingWithoutVerifiedCompletionEvidence() {
        Payment payment = validCapturedPayment();

        BookingParticipants disputed = new BookingParticipants(
                "booking-001", null, "listing-001", "customer-001",
                "provider-001", BookingStatus.DISPUTED, null, null);

        when(paymentPersistenceService.findPaymentById("payment-001"))
                .thenReturn(Optional.of(payment));
        when(bookingFacade.getBookingParticipants("booking-001"))
                .thenReturn(disputed);
        when(completionLookup.findConfirmedAt("booking-001"))
                .thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.recordPendingEarnings("payment-001"));

        assertEquals(
                "Pending earnings require verified service completion",
                exception.getMessage());

        verifyNoInteractions(earningsLedgerService);
    }

    /**
     * Creates a payment with a 15% commission snapshot.
     */
    private Payment validCapturedPayment() {
        Payment payment = new Payment();

        payment.setId("payment-001");
        payment.setBookingId("booking-001");
        payment.setCustomerId("customer-001");
        payment.setAmount(new BigDecimal("1000.00"));
        payment.setCommissionAmount(new BigDecimal("150.00"));
        payment.setCurrency("LKR");
        payment.setStatus(PaymentStatus.CAPTURED);

        return payment;
    }

    /**
     * Returns booking participants from a trusted mocked facade.
     */
    private BookingParticipants validParticipants() {
        return new BookingParticipants(
                "booking-001",
                null,
                "listing-001",
                "customer-001",
                "provider-001",
                BookingStatus.COMPLETED,
                null,
                null);
    }
}
