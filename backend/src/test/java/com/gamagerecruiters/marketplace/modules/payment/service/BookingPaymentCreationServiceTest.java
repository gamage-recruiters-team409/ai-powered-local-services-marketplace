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

import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.gamagerecruiters.marketplace.modules.payment.gateway.StripeCheckoutGateway;
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
                paymentRepository,
                new StripeCheckoutGateway(
                        new StripeGatewayProperties(false, "", "")));
    }

    /**
     * Every currency supported by the configured Checkout gateway
     * must remain eligible for initial payment persistence.
     */
    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR", "GBP", "LKR"})
    void createsPaymentForSupportedGatewayCurrency(String currency) {

        stubCurrencyQuote(currency);

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        when(paymentRepository.insert(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertEquals(currency, result.getCurrency());
        assertEquals(AMOUNT, result.getAmount());
        assertEquals(PaymentStatus.INITIATED, result.getStatus());
        assertNull(result.getGatewayRef());
        assertNull(result.getCheckoutReservationToken());

        verify(paymentRepository).insert(result);

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    /**
     * Valid ISO currencies that are unsupported by the configured
     * gateway must never create a new financial record.
     */
    @ParameterizedTest
    @ValueSource(strings = {"JPY", "AUD", "CAD", "CHF"})
    void rejectsUnsupportedGatewayCurrencyBeforePersistence(
            String currency) {

        stubCurrencyQuote(currency);

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        assertTrue(exception.getMessage().contains(
                "not supported by the checkout gateway"));

        verify(paymentRepository).findByBookingId(BOOKING_ID);
        verifyNoPaymentWrites();
    }

    @Test
    void repeatedUnsupportedCurrencyRequestsNeverPersistPayments() {

        stubCurrencyQuote("JPY");

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        for (int attempt = 0; attempt < 3; attempt++) {

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.createOrGetPayment(
                            BOOKING_ID, CUSTOMER_ID));

            assertTrue(exception.getMessage().contains(
                    "not supported by the checkout gateway"));
        }

        verify(paymentRepository, times(3))
                .findByBookingId(BOOKING_ID);

        verifyNoPaymentWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "EUR", "GBP", "LKR"})
    void reusesExistingSupportedCurrencyPayment(String currency) {

        stubCurrencyQuote(currency);

        Payment existing = creationService.createPayment(
                BOOKING_ID,
                CUSTOMER_ID,
                AMOUNT,
                currency,
                COMMISSION_RATE);

        existing.setId("existing-supported-payment");
        existing.setGatewayRef("cs_existing_supported");

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        Payment result = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertSame(existing, result);
        assertEquals(currency, result.getCurrency());
        assertEquals("cs_existing_supported", result.getGatewayRef());

        verifyNoPaymentWrites();
    }

    /**
     * The new persistence restriction must not rewrite or replace
     * historical payment records.
     */
    @Test
    void preservesExistingHistoricalUnsupportedCurrencyPayment() {

        stubCurrencyQuote("JPY");

        Payment existing = creationService.createPayment(
                BOOKING_ID,
                CUSTOMER_ID,
                AMOUNT,
                "JPY",
                COMMISSION_RATE);

        existing.setId("historical-payment");
        existing.setGatewayRef("cs_historical");

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        Payment result = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertSame(existing, result);
        assertEquals("JPY", result.getCurrency());
        assertEquals("cs_historical", result.getGatewayRef());
        assertEquals(AMOUNT, result.getAmount());

        verifyNoPaymentWrites();
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
        stubExpiredQuote();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verify(paymentRepository).findByBookingId(BOOKING_ID);
        verifyNoPaymentWrites();
    }

    /**
     * An attached session remains available for read-only inspection
     * after the initiation deadline without creating another payment.
     */
    @Test
    void reusesAttachedSessionAfterAuthoritativeDeadline() {
        stubExpiredQuote();

        Payment existing = existingPayment();
        existing.setGatewayRef("cs_existing");

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        Payment result = service.createOrGetPayment(
                BOOKING_ID, CUSTOMER_ID);

        assertSame(existing, result);
        assertEquals("cs_existing", result.getGatewayRef());
        assertEquals(PaymentStatus.INITIATED, result.getStatus());
        assertNull(result.getGatewayPaymentIntentId());

        verifyNoPaymentWrites();
    }

    @Test
    void rejectsExistingPaymentWithoutSessionAfterDeadline() {
        stubExpiredQuote();

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existingPayment()));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoPaymentWrites();
    }

    @Test
    void rejectsBlankAttachedSessionAfterDeadline() {
        stubExpiredQuote();

        Payment existing = existingPayment();
        existing.setGatewayRef(" ");

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoPaymentWrites();
    }

    @Test
    void rejectsAttachedSessionWithReservationAfterDeadline() {
        stubExpiredQuote();

        Payment existing = existingPayment();
        existing.setGatewayRef("cs_existing");
        existing.setCheckoutReservationToken("reservation-001");
        existing.setCheckoutSubmissionStarted(true);

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        assertEquals("reservation-001",
                existing.getCheckoutReservationToken());

        assertEquals(Boolean.TRUE,
                existing.getCheckoutSubmissionStarted());

        verifyNoPaymentWrites();
    }

    @Test
    void rejectsUnauthorizedAttachedPaymentAfterDeadline() {
        stubExpiredQuote();

        Payment existing = existingPayment();
        existing.setGatewayRef("cs_existing");
        existing.setCustomerId("different-customer");

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(AccessDeniedException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoPaymentWrites();
    }

    @Test
    void rejectsAttachedPaymentWithFinancialMismatchAfterDeadline() {
        stubExpiredQuote();

        Payment existing = existingPayment();
        existing.setGatewayRef("cs_existing");
        existing.setAmount(new BigDecimal("5000.00"));

        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> service.createOrGetPayment(
                        BOOKING_ID, CUSTOMER_ID));

        verifyNoPaymentWrites();
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

    /**
     * Represents an elapsed initiation window while booking
     * eligibility is controlled by the authorization service.
     */
    private void stubExpiredQuote() {
        Instant now = Instant.now();

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        AMOUNT,
                        "LKR",
                        COMMISSION_RATE,
                        now.minusSeconds(1200),
                        now.minusSeconds(300)));
    }

    /**
     * Read-only reconciliation must never insert or overwrite payments.
     */
    private void verifyNoPaymentWrites() {
        verify(paymentRepository, never())
                .insert(any(Payment.class));

        verify(paymentRepository, never())
                .save(any(Payment.class));
    }

    /**
     * Supplies trusted financial information with a valid
     * authoritative initiation window and the specified currency.
     */
    private void stubCurrencyQuote(String currency) {

        Instant now = Instant.now();

        when(quoteProvider.getPaymentQuote(BOOKING_ID))
                .thenReturn(new BookingPaymentQuote(
                        AMOUNT,
                        currency,
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
