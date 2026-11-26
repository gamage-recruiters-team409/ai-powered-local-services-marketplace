package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider.BookingPaymentQuote;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Verifies trusted payment creation, financial validation,
 * authorization and duplicate-key recovery.
 */
@ExtendWith(MockitoExtension.class)
class BookingPaymentCreationServiceTest {

    private static final String BOOKING_ID = "booking-001";
    private static final String CUSTOMER_ID = "customer-001";

    private static final BigDecimal AMOUNT =
            new BigDecimal("10000.00");

    private static final BigDecimal COMMISSION_RATE =
            new BigDecimal("0.10");

    @Mock
    private PaymentBookingAuthorizationService authorizationService;

    @Mock
    private TrustedBookingPaymentQuoteProvider quoteProvider;

    @Mock
    private PaymentRepository paymentRepository;

    private PaymentCreationService creationService;

    private BookingPaymentCreationService service;

    @BeforeEach
    void setUp() {
        creationService = new PaymentCreationService(
                new CommissionCalculator());

        service = new BookingPaymentCreationService(
                authorizationService,
                quoteProvider,
                creationService,
                paymentRepository);
    }

    @Test
    void createsPaymentUsingTrustedFinancialInformation() {
        stubValidQuote();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        when(paymentRepository.insert(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertNotNull(payment.getId());
        assertEquals(BOOKING_ID, payment.getBookingId());
        assertEquals(CUSTOMER_ID, payment.getCustomerId());
        assertEquals(AMOUNT, payment.getAmount());
        assertEquals("LKR", payment.getCurrency());
        assertEquals(COMMISSION_RATE, payment.getCommissionRate());
        assertEquals(new BigDecimal("1000.00"),
                payment.getCommissionAmount());

        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
        assertEquals(new BigDecimal("0.00"),
                payment.getRefundedAmount());

        assertNotNull(payment.getCreatedAt());
        assertNotNull(payment.getUpdatedAt());
        assertNull(payment.getGatewayRef());
        assertNull(payment.getGatewayPaymentIntentId());

        verify(authorizationService)
                .requirePaymentInitiationStatus(
                        BOOKING_ID, CUSTOMER_ID);

        verify(paymentRepository).insert(payment);

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    @Test
    void normalizesIdentifiersBeforeAuthorization() {
        stubValidQuote();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        when(paymentRepository.insert(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment payment = service.createOrGetPayment(
                "  " + BOOKING_ID + "  ",
                "  " + CUSTOMER_ID + "  ");

        assertEquals(BOOKING_ID, payment.getBookingId());
        assertEquals(CUSTOMER_ID, payment.getCustomerId());

        verify(authorizationService)
                .requirePaymentInitiationStatus(
                        BOOKING_ID, CUSTOMER_ID);
    }

    @Test
    void producesStableInitialPaymentIdentifier() {
        stubValidQuote();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        when(paymentRepository.insert(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment first = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        Payment second = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertEquals(first.getId(), second.getId());
    }

    @Test
    void reusesExistingPaymentWithoutOverwritingGatewayData() {
        stubValidQuote();

        Payment existing = existingPayment();

        existing.setGatewayRef("cs_existing");
        existing.setCheckoutReservationToken("reservation-001");
        existing.setCheckoutSubmissionStarted(true);

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        Payment result = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertSame(existing, result);
        assertEquals("cs_existing", result.getGatewayRef());
        assertEquals("reservation-001",
                result.getCheckoutReservationToken());

        assertEquals(Boolean.TRUE,
                result.getCheckoutSubmissionStarted());

        verify(paymentRepository, never())
                .insert(any(Payment.class));

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    @Test
    void rejectsUnauthorizedCustomerBeforeReadingQuote() {
        when(authorizationService.requirePaymentInitiationStatus(
                BOOKING_ID, CUSTOMER_ID))
                .thenThrow(new AccessDeniedException("Access denied"));

        assertThrows(AccessDeniedException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoInteractions(quoteProvider, paymentRepository);
    }

    @Test
    void rejectsIneligibleBookingBeforeReadingQuote() {
        when(authorizationService.requirePaymentInitiationStatus(
                BOOKING_ID, CUSTOMER_ID))
                .thenThrow(new IllegalStateException(
                        "Booking is not awaiting payment"));

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoInteractions(quoteProvider, paymentRepository);
    }

    @Test
    void rejectsMissingTrustedQuote() {
        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsExpiredAuthoritativeDeadline() {
        Instant now = Instant.now();

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        AMOUNT,
                        "LKR",
                        COMMISSION_RATE,
                        now.minusSeconds(1200),
                        now.minusSeconds(300)));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsInvalidTrustedAmount() {
        Instant now = Instant.now();

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        new BigDecimal("-100.00"),
                        "LKR",
                        COMMISSION_RATE,
                        now.minusSeconds(60),
                        now.plusSeconds(840)));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsExistingPaymentOwnedByAnotherCustomer() {
        stubValidQuote();

        Payment existing = existingPayment();
        existing.setCustomerId("different-customer");

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(AccessDeniedException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verify(paymentRepository, never())
                .insert(any(Payment.class));
    }

    @Test
    void rejectsExistingPaymentWithDifferentAmount() {
        stubValidQuote();

        Payment existing = existingPayment();
        existing.setAmount(new BigDecimal("5000.00"));

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verify(paymentRepository, never())
                .insert(any(Payment.class));
    }

    @Test
    void rejectsExistingPaymentWithDifferentCommission() {
        stubValidQuote();

        Payment existing = existingPayment();
        existing.setCommissionAmount(new BigDecimal("500.00"));

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verify(paymentRepository, never())
                .insert(any(Payment.class));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = "INITIATED",
            mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonReusablePaymentStatuses(PaymentStatus status) {
        stubValidQuote();

        Payment existing = existingPayment();
        existing.setStatus(status);

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verify(paymentRepository, never())
                .insert(any(Payment.class));
    }

    @Test
    void rejectsMultipleExistingPaymentAttempts() {
        stubValidQuote();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(
                        existingPayment(), existingPayment()));

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verify(paymentRepository, never())
                .insert(any(Payment.class));
    }

    @Test
    void recoversExistingPaymentAfterDuplicateInsert() {
        stubValidQuote();

        Payment existing = existingPayment();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        when(paymentRepository.insert(any(Payment.class)))
                .thenAnswer(invocation -> {
                    Payment candidate = invocation.getArgument(0);
                    existing.setId(candidate.getId());

                    throw new DuplicateKeyException(
                            "Concurrent initial payment");
                });

        when(paymentRepository.findById(anyString()))
                .thenReturn(Optional.of(existing));

        Payment result = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertSame(existing, result);

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    @Test
    void rejectsUnresolvedDuplicateInsertConflict() {
        stubValidQuote();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        when(paymentRepository.insert(any(Payment.class)))
                .thenThrow(new DuplicateKeyException("Conflict"));

        when(paymentRepository.findById(anyString()))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t"})
    void rejectsInvalidBookingIdentifier(String bookingId) {
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        bookingId, CUSTOMER_ID));

        verifyNoInteractions(
                authorizationService,
                quoteProvider,
                paymentRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t"})
    void rejectsInvalidCustomerIdentifier(String customerId) {
        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, customerId));

        verifyNoInteractions(
                authorizationService,
                quoteProvider,
                paymentRepository);
    }

    @Test
    void rejectsCreationInsideActiveTransaction() {
        TransactionSynchronizationManager
                .setActualTransactionActive(true);

        try {
            assertThrows(IllegalStateException.class,
                    () -> service.createOrGetPayment(
                            BOOKING_ID, CUSTOMER_ID));

            verifyNoInteractions(
                    authorizationService,
                    quoteProvider,
                    paymentRepository);

        } finally {
            TransactionSynchronizationManager
                    .setActualTransactionActive(false);
        }
    }

    private void stubValidQuote() {
        Instant now = Instant.now();

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        AMOUNT,
                        "LKR",
                        COMMISSION_RATE,
                        now.minusSeconds(60),
                        now.plusSeconds(840)));
    }

    private Payment existingPayment() {
        Payment payment = creationService.createPayment(
                BOOKING_ID,
                CUSTOMER_ID,
                AMOUNT,
                "LKR",
                COMMISSION_RATE);

        payment.setId("existing-payment-001");

        return payment;
    }
}
