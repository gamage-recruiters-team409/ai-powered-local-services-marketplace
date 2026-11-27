package com.gamagerecruiters.marketplace.modules.payment.gateway;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider.BookingPaymentQuote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the trusted WP05 booking payment quote adapter.
 *
 * <p>
 * Verifies authoritative financial mapping, booking eligibility,
 * fail-closed validation and preservation of the existing booking
 * authorization boundary.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class BookingFacadePaymentQuoteAdapterTest {

    private static final String BOOKING_ID = "booking-001";
    private static final String CUSTOMER_ID = "customer-001";

    private static final BigDecimal AMOUNT = new BigDecimal("10000.00");

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.10");

    private static final Instant PRICE_CAPTURED_AT = Instant.parse("2026-11-26T07:00:00Z");

    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-26T08:00:00Z");

    private static final Instant PAYMENT_DUE_AT = Instant.parse("2026-11-26T08:15:00Z");

    @Mock
    private BookingFacade bookingFacade;

    private BookingFacadePaymentQuoteAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BookingFacadePaymentQuoteAdapter(bookingFacade);
    }

    // ---------------------------------------------------------
    // Authoritative quote mapping
    // ---------------------------------------------------------

    @Test
    void shouldMapAuthoritativeBookingPaymentQuote() {

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(validSnapshot());

        BookingPaymentQuote quote = adapter.getPaymentQuote(BOOKING_ID);

        assertAll(
                () -> assertEquals(AMOUNT, quote.amount()),
                () -> assertEquals("LKR", quote.currency()),
                () -> assertEquals(
                        COMMISSION_RATE, quote.commissionRate()),
                () -> assertEquals(
                        ACCEPTED_AT, quote.providerAcceptedAt()),
                () -> assertEquals(
                        PAYMENT_DUE_AT, quote.paymentDueAt()));

        verify(bookingFacade).getPaymentSnapshot(BOOKING_ID);
        verifyNoMoreInteractions(bookingFacade);
    }

    @Test
    void shouldNormalizeBookingIdentifierBeforeLookup() {

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(validSnapshot());

        BookingPaymentQuote quote = adapter.getPaymentQuote("  " + BOOKING_ID + "  ");

        assertEquals(AMOUNT, quote.amount());

        verify(bookingFacade).getPaymentSnapshot(BOOKING_ID);
    }

    @Test
    void shouldRejectMissingBookingFacadeDependency() {

        assertThrows(
                NullPointerException.class,
                () -> new BookingFacadePaymentQuoteAdapter(null));

        verifyNoInteractions(bookingFacade);
    }

    // ---------------------------------------------------------
    // Booking identity and authorization
    // ---------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   ", "\t" })
    void shouldRejectInvalidBookingIdentifier(String bookingId) {

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.getPaymentQuote(bookingId));

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void shouldRejectUnavailableBookingSnapshot() {

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.getPaymentQuote(BOOKING_ID));
    }

    @Test
    void shouldRejectMismatchedBookingIdentity() {

        assertInvalidSnapshot(snapshot(
                "different-booking",
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " " })
    void shouldRejectMissingCustomerIdentity(String customerId) {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                customerId,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    @Test
    void shouldPropagateBookingAuthorizationFailure() {

        AccessDeniedException denied = new AccessDeniedException("Booking access denied");

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenThrow(denied);

        AccessDeniedException actual = assertThrows(
                AccessDeniedException.class,
                () -> adapter.getPaymentQuote(BOOKING_ID));

        // Preserve the original authorization failure.
        assertSame(denied, actual);

        verify(bookingFacade).getPaymentSnapshot(BOOKING_ID);
        verifyNoMoreInteractions(bookingFacade);
    }

    @Test
    void shouldPropagateBookingLookupFailure() {

        IllegalStateException failure = new IllegalStateException("Booking lookup failed");

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenThrow(failure);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> adapter.getPaymentQuote(BOOKING_ID));

        assertSame(failure, actual);
    }

    // ---------------------------------------------------------
    // Booking payment eligibility
    // ---------------------------------------------------------

    @Test
    void shouldRejectAllNonPayableBookingStatuses() {

        for (BookingStatus status : BookingStatus.values()) {

            if (status == BookingStatus.AWAITING_PAYMENT) {
                continue;
            }

            when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                    .thenReturn(snapshot(
                            BOOKING_ID,
                            CUSTOMER_ID,
                            status,
                            AMOUNT,
                            "LKR",
                            COMMISSION_RATE,
                            PRICE_CAPTURED_AT,
                            ACCEPTED_AT,
                            PAYMENT_DUE_AT));

            assertThrows(
                    IllegalStateException.class,
                    () -> adapter.getPaymentQuote(BOOKING_ID),
                    "Booking status must not permit payment: " + status);
        }
    }

    @Test
    void shouldRejectMissingBookingStatus() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                null,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    // ---------------------------------------------------------
    // Authoritative payment amount validation
    // ---------------------------------------------------------

    @Test
    void shouldRejectMissingPaymentAmount() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                null,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "0.00", "-1", "-10000.00" })
    void shouldRejectNonPositivePaymentAmount(String amount) {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                new BigDecimal(amount),
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    // ---------------------------------------------------------
    // Currency validation
    // ---------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "INVALID",
            "ZZZ",
            "LK",
            "lkr"
    })
    void shouldRejectInvalidCurrency(String currency) {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                currency,
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    // ---------------------------------------------------------
    // Commission rate validation
    // ---------------------------------------------------------

    @Test
    void shouldRejectMissingCommissionRate() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                null,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-0.01",
            "-1",
            "1.01",
            "10"
    })
    void shouldRejectOutOfRangeCommissionRate(String rate) {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                new BigDecimal(rate),
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "0.10",
            "0.50",
            "1"
    })
    void shouldAcceptValidCommissionRate(String rate) {

        BigDecimal commissionRate = new BigDecimal(rate);

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(snapshot(
                        BOOKING_ID,
                        CUSTOMER_ID,
                        BookingStatus.AWAITING_PAYMENT,
                        AMOUNT,
                        "LKR",
                        commissionRate,
                        PRICE_CAPTURED_AT,
                        ACCEPTED_AT,
                        PAYMENT_DUE_AT));

        BookingPaymentQuote quote = adapter.getPaymentQuote(BOOKING_ID);

        assertEquals(commissionRate, quote.commissionRate());
    }

    // ---------------------------------------------------------
    // Financial timestamp validation
    // ---------------------------------------------------------

    @Test
    void shouldRejectMissingPriceCaptureTimestamp() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                null,
                ACCEPTED_AT,
                PAYMENT_DUE_AT));
    }

    @Test
    void shouldRejectMissingProviderAcceptanceTimestamp() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                null,
                PAYMENT_DUE_AT));
    }

    @Test
    void shouldRejectMissingPaymentDeadline() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                null));
    }

    @Test
    void shouldRejectDeadlineBeforeProviderAcceptance() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                ACCEPTED_AT.minusSeconds(1)));
    }

    @Test
    void shouldRejectDeadlineEqualToProviderAcceptance() {

        assertInvalidSnapshot(snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                ACCEPTED_AT));
    }

    // ---------------------------------------------------------
    // Test fixtures
    // ---------------------------------------------------------

    /**
     * Creates a valid authoritative booking payment snapshot.
     */
    private BookingPaymentSnapshot validSnapshot() {

        return snapshot(
                BOOKING_ID,
                CUSTOMER_ID,
                BookingStatus.AWAITING_PAYMENT,
                AMOUNT,
                "LKR",
                COMMISSION_RATE,
                PRICE_CAPTURED_AT,
                ACCEPTED_AT,
                PAYMENT_DUE_AT);
    }

    /**
     * Configures an invalid snapshot and verifies fail-closed behavior.
     */
    private void assertInvalidSnapshot(
            BookingPaymentSnapshot snapshot) {

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(snapshot);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.getPaymentQuote(BOOKING_ID));

        verify(bookingFacade).getPaymentSnapshot(BOOKING_ID);
    }

    /**
     * Builds a booking snapshot with explicitly controlled financial
     * and lifecycle values for validation tests.
     */
    private BookingPaymentSnapshot snapshot(
            String bookingId,
            String customerId,
            BookingStatus status,
            BigDecimal amount,
            String currency,
            BigDecimal commissionRate,
            Instant priceCapturedAt,
            Instant acceptedAt,
            Instant paymentDueAt) {

        return new BookingPaymentSnapshot(
                bookingId,
                "BOOK-001",
                customerId,
                "provider-001",
                "listing-001",
                status,
                amount,
                currency,
                commissionRate,
                priceCapturedAt,
                acceptedAt,
                paymentDueAt);
    }
}