package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Unit tests for verified payment-to-booking confirmation.
 *
 * <p>
 * Tests cover financial snapshot consistency, trusted authentication,
 * transaction preconditions and security context restoration.
 * </p>
 *
 * <p>
 * MongoDB transaction atomicity is verified separately through
 * integration tests.
 * </p>
 */
class BookingPaymentConfirmationServiceTest {

    private static final String PAYMENT_ID = "pay_123";
    private static final String BOOKING_ID = "booking_123";
    private static final String CUSTOMER_ID = "customer_123";

    private static final BigDecimal AMOUNT = new BigDecimal("10000.00");

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.10");

    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-26T08:00:00Z");

    private static final Instant PAYMENT_DUE_AT = ACCEPTED_AT.plusSeconds(900);

    private BookingFacade bookingFacade;
    private BookingPaymentConfirmationService service;
    private BookingPaymentSnapshot snapshot;
    private BookingParticipants confirmedBooking;
    private Payment payment;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        TransactionSynchronizationManager
                .setActualTransactionActive(true);

        bookingFacade = mock(BookingFacade.class);

        service = new BookingPaymentConfirmationService(
                bookingFacade);

        snapshot = mock(BookingPaymentSnapshot.class);
        confirmedBooking = mock(BookingParticipants.class);

        payment = new Payment();
        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        payment.setCustomerId(CUSTOMER_ID);
        payment.setAmount(AMOUNT);
        payment.setCurrency("LKR");
        payment.setCommissionRate(COMMISSION_RATE);
        payment.setCommissionAmount(
                new BigDecimal("1000.00"));
        payment.setStatus(PaymentStatus.INITIATED);

        when(snapshot.bookingId()).thenReturn(BOOKING_ID);
        when(snapshot.customerId()).thenReturn(CUSTOMER_ID);
        when(snapshot.amount()).thenReturn(AMOUNT);
        when(snapshot.currency()).thenReturn("LKR");
        when(snapshot.commissionRate())
                .thenReturn(COMMISSION_RATE);

        when(snapshot.priceCapturedAt())
                .thenReturn(ACCEPTED_AT.minusSeconds(60));

        when(snapshot.acceptedAt())
                .thenReturn(ACCEPTED_AT);

        when(snapshot.paymentDueAt())
                .thenReturn(PAYMENT_DUE_AT);

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(snapshot);

        when(bookingFacade.markPaymentConfirmed(
                BOOKING_ID, PAYMENT_ID))
                .thenReturn(confirmedBooking);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();

        TransactionSynchronizationManager
                .setActualTransactionActive(false);
    }

    /**
     * A valid verified payment must use the authoritative
     * booking snapshot and stable internal payment reference.
     */
    @Test
    void validPaymentConfirmsBooking() {
        assertSame(
                confirmedBooking,
                service.confirmVerifiedPayment(payment));

        var order = inOrder(bookingFacade);

        order.verify(bookingFacade)
                .getPaymentSnapshot(BOOKING_ID);

        order.verify(bookingFacade)
                .markPaymentConfirmed(
                        BOOKING_ID, PAYMENT_ID);

        assertNull(SecurityContextHolder.getContext()
                .getAuthentication());
    }

    /**
     * Spring Security anonymous authentication must not prevent
     * a verified webhook from entering the WP05 trust boundary.
     */
    @Test
    void anonymousAuthenticationAllowsTrustedConfirmation() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "wp07-test-key",
                "anonymousUser",
                List.of(new SimpleGrantedAuthority(
                        "ROLE_ANONYMOUS")));

        SecurityContext original = SecurityContextHolder.createEmptyContext();

        original.setAuthentication(anonymous);
        SecurityContextHolder.setContext(original);

        when(bookingFacade.markPaymentConfirmed(
                BOOKING_ID, PAYMENT_ID))
                .thenAnswer(invocation -> {

                    var authentication = SecurityContextHolder.getContext()
                            .getAuthentication();

                    assertNotNull(authentication);

                    assertEquals(
                            "SYSTEM:PAYMENT_PROCESSING",
                            authentication.getPrincipal());

                    assertTrue(authentication.isAuthenticated());
                    assertTrue(authentication.getAuthorities().isEmpty());

                    return confirmedBooking;
                });

        assertSame(
                confirmedBooking,
                service.confirmVerifiedPayment(payment));

        assertSame(
                original,
                SecurityContextHolder.getContext());

        assertSame(
                anonymous,
                SecurityContextHolder.getContext()
                        .getAuthentication());
    }

    /**
     * An authenticated application user must never be elevated
     * to trusted payment-processing privileges.
     */
    @ParameterizedTest
    @MethodSource("authenticatedRoles")
    void authenticatedUsersCannotElevate(String role) {
        var authentication = new UsernamePasswordAuthenticationToken(
                "authenticated-user",
                "credentials",
                List.of(new SimpleGrantedAuthority(role)));

        SecurityContext original = SecurityContextHolder.createEmptyContext();

        original.setAuthentication(authentication);
        SecurityContextHolder.setContext(original);

        assertThrows(
                IllegalStateException.class,
                () -> service.confirmVerifiedPayment(payment));

        verifyNoInteractions(bookingFacade);

        assertSame(
                original,
                SecurityContextHolder.getContext());

        assertSame(
                authentication,
                SecurityContextHolder.getContext()
                        .getAuthentication());
    }

    private static Stream<String> authenticatedRoles() {
        return Stream.of(
                "ROLE_CUSTOMER",
                "ROLE_PROVIDER",
                "ROLE_ADMIN",
                "ROLE_SUPPORT_ADMIN",
                "ROLE_FINANCE_ADMIN",
                "ROLE_MODERATOR",
                "ROLE_SUPER_ADMIN");
    }

    @Test
    void confirmationRequiresActiveTransaction() {
        TransactionSynchronizationManager
                .setActualTransactionActive(false);

        assertThrows(
                IllegalStateException.class,
                () -> service.confirmVerifiedPayment(payment));

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void nullPaymentIsRejected() {
        assertThrows(
                NullPointerException.class,
                () -> service.confirmVerifiedPayment(null));

        verifyNoInteractions(bookingFacade);
    }

    /**
     * Invalid identifiers must be rejected before accessing WP05.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPaymentIdentifiers")
    void invalidPaymentIdentifiersAreRejected(
            String description,
            Consumer<Payment> mutation) {

        mutation.accept(payment);

        assertThrows(
                IllegalStateException.class,
                () -> service.confirmVerifiedPayment(payment));

        verifyNoInteractions(bookingFacade);
    }

    private static Stream<Arguments> invalidPaymentIdentifiers() {
        return Stream.of(
                Arguments.of(
                        "Missing payment ID",
                        (Consumer<Payment>) p -> p.setId(null)),
                Arguments.of(
                        "Blank payment ID",
                        (Consumer<Payment>) p -> p.setId(" ")),
                Arguments.of(
                        "Invalid payment reference",
                        (Consumer<Payment>) p -> p.setId("pay/123")),
                Arguments.of(
                        "Oversized payment reference",
                        (Consumer<Payment>) p -> p.setId("a".repeat(101))),
                Arguments.of(
                        "Missing booking ID",
                        (Consumer<Payment>) p -> p.setBookingId(null)),
                Arguments.of(
                        "Blank booking ID",
                        (Consumer<Payment>) p -> p.setBookingId(" ")),
                Arguments.of(
                        "Missing customer ID",
                        (Consumer<Payment>) p -> p.setCustomerId(null)),
                Arguments.of(
                        "Blank customer ID",
                        (Consumer<Payment>) p -> p.setCustomerId(" ")));
    }

    @Test
    void missingSnapshotPreventsConfirmation() {
        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(null);

        assertConfirmationRejected();
    }

    @Test
    void mismatchedBookingIdentityPreventsConfirmation() {
        when(snapshot.bookingId())
                .thenReturn("different-booking");

        assertConfirmationRejected();
    }

    @Test
    void mismatchedCustomerIdentityPreventsConfirmation() {
        when(snapshot.customerId())
                .thenReturn("different-customer");

        assertConfirmationRejected();
    }

    /**
     * Invalid persisted financial values must not result in
     * a booking confirmation.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPaymentFinancialValues")
    void invalidPaymentFinancialValuesAreRejected(
            String description,
            Consumer<Payment> mutation) {

        mutation.accept(payment);

        assertConfirmationRejected();
    }

    private static Stream<Arguments> invalidPaymentFinancialValues() {
        return Stream.of(
                Arguments.of(
                        "Missing amount",
                        (Consumer<Payment>) p -> p.setAmount(null)),
                Arguments.of(
                        "Zero amount",
                        (Consumer<Payment>) p -> p.setAmount(BigDecimal.ZERO)),
                Arguments.of(
                        "Negative amount",
                        (Consumer<Payment>) p -> p.setAmount(
                                new BigDecimal("-100.00"))),
                Arguments.of(
                        "Mismatched amount",
                        (Consumer<Payment>) p -> p.setAmount(
                                new BigDecimal("9999.00"))),
                Arguments.of(
                        "Missing currency",
                        (Consumer<Payment>) p -> p.setCurrency(null)),
                Arguments.of(
                        "Blank currency",
                        (Consumer<Payment>) p -> p.setCurrency(" ")),
                Arguments.of(
                        "Mismatched currency",
                        (Consumer<Payment>) p -> p.setCurrency("USD")),
                Arguments.of(
                        "Missing commission rate",
                        (Consumer<Payment>) p -> p.setCommissionRate(null)),
                Arguments.of(
                        "Mismatched commission rate",
                        (Consumer<Payment>) p -> p.setCommissionRate(
                                new BigDecimal("0.20"))),
                Arguments.of(
                        "Missing commission amount",
                        (Consumer<Payment>) p -> p.setCommissionAmount(null)),
                Arguments.of(
                        "Mismatched commission amount",
                        (Consumer<Payment>) p -> p.setCommissionAmount(
                                new BigDecimal("999.00"))));
    }

    @Test
    void missingSnapshotAmountIsRejected() {
        when(snapshot.amount()).thenReturn(null);

        assertConfirmationRejected();
    }

    @Test
    void missingSnapshotCurrencyIsRejected() {
        when(snapshot.currency()).thenReturn(null);

        assertConfirmationRejected();
    }

    @ParameterizedTest
    @MethodSource("invalidCommissionRates")
    void invalidSnapshotCommissionRateIsRejected(
            BigDecimal rate) {

        when(snapshot.commissionRate()).thenReturn(rate);

        assertConfirmationRejected();
    }

    private static Stream<BigDecimal> invalidCommissionRates() {
        return Stream.of(
                null,
                new BigDecimal("-0.01"),
                new BigDecimal("1.01"));
    }

    @Test
    void missingPriceCaptureTimestampIsRejected() {
        when(snapshot.priceCapturedAt()).thenReturn(null);

        assertConfirmationRejected();
    }

    @Test
    void missingAcceptanceTimestampIsRejected() {
        when(snapshot.acceptedAt()).thenReturn(null);

        assertConfirmationRejected();
    }

    @Test
    void missingPaymentDeadlineIsRejected() {
        when(snapshot.paymentDueAt()).thenReturn(null);

        assertConfirmationRejected();
    }

    @Test
    void deadlineEqualToAcceptanceIsRejected() {
        when(snapshot.paymentDueAt()).thenReturn(ACCEPTED_AT);

        assertConfirmationRejected();
    }

    @Test
    void deadlineBeforeAcceptanceIsRejected() {
        when(snapshot.paymentDueAt())
                .thenReturn(ACCEPTED_AT.minusSeconds(1));

        assertConfirmationRejected();
    }

    /**
     * Monetary equality must not depend on BigDecimal scale.
     */
    @Test
    void equivalentDecimalScalesAreAccepted() {
        payment.setAmount(new BigDecimal("10000.000"));
        payment.setCommissionRate(new BigDecimal("0.1000"));
        payment.setCommissionAmount(new BigDecimal("1000.000"));

        assertSame(
                confirmedBooking,
                service.confirmVerifiedPayment(payment));
    }

    @Test
    void equivalentCurrencyCaseIsAccepted() {
        payment.setCurrency("lkr");

        assertSame(
                confirmedBooking,
                service.confirmVerifiedPayment(payment));
    }

    /**
     * Repeated calls must use the same payment reference.
     * Actual booking idempotency is enforced by WP05.
     */
    @Test
    void repeatedConfirmationUsesStableReference() {
        assertSame(
                confirmedBooking,
                service.confirmVerifiedPayment(payment));

        assertSame(
                confirmedBooking,
                service.confirmVerifiedPayment(payment));

        verify(bookingFacade, times(2))
                .markPaymentConfirmed(
                        BOOKING_ID, PAYMENT_ID);
    }

    @Test
    void missingConfirmationResultIsRejected() {
        when(bookingFacade.markPaymentConfirmed(
                BOOKING_ID, PAYMENT_ID))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> service.confirmVerifiedPayment(payment));

        assertNull(SecurityContextHolder.getContext()
                .getAuthentication());
    }

    /**
     * Booking failures must propagate to the surrounding
     * payment transaction and restore the original context.
     */
    @Test
    void bookingFailureRestoresAnonymousContext() {
        var anonymous = new AnonymousAuthenticationToken(
                "wp07-test-key",
                "anonymousUser",
                List.of(new SimpleGrantedAuthority(
                        "ROLE_ANONYMOUS")));

        SecurityContext original = SecurityContextHolder.createEmptyContext();

        original.setAuthentication(anonymous);
        SecurityContextHolder.setContext(original);

        IllegalStateException failure = new IllegalStateException(
                "Booking confirmation failed");

        when(bookingFacade.markPaymentConfirmed(
                BOOKING_ID, PAYMENT_ID))
                .thenThrow(failure);

        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> service.confirmVerifiedPayment(payment)));

        assertSame(
                original,
                SecurityContextHolder.getContext());

        assertSame(
                anonymous,
                SecurityContextHolder.getContext()
                        .getAuthentication());
    }

    @Test
    void snapshotFailureRestoresOriginalContext() {
        SecurityContext original = SecurityContextHolder.getContext();

        IllegalStateException failure = new IllegalStateException(
                "Booking snapshot unavailable");

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenThrow(failure);

        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> service.confirmVerifiedPayment(payment)));

        assertSame(
                original,
                SecurityContextHolder.getContext());

        assertNull(original.getAuthentication());

        verify(bookingFacade, never())
                .markPaymentConfirmed(anyString(), anyString());
    }

    /**
     * Shared assertion for invalid financial snapshot scenarios.
     */
    private void assertConfirmationRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> service.confirmVerifiedPayment(payment));

        verify(bookingFacade, never())
                .markPaymentConfirmed(anyString(), anyString());

        assertNull(SecurityContextHolder.getContext()
                .getAuthentication());
    }
}