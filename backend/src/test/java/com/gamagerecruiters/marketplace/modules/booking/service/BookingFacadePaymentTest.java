package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingFacadePaymentTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:20:00Z");
    private static final Instant ACCEPTED = Instant.parse("2026-11-20T04:00:00Z");
    private static final Instant DUE = Instant.parse("2026-11-20T04:15:00Z");
    private static final String CUSTOMER = "c-1";
    private static final String PROVIDER = "p-1";
    private static final String PAY_REF = "cs_test_a1B2c3";
    private static final String OTHER_REF = "cs_test_zzz999";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private BookingNotifier notifier;

    private BookingFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new BookingFacadeImpl(bookingRepository, new BookingPaymentPolicy(15, 15), notifier,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static <T> T asPaymentModule(Supplier<T> action) {
        return TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING, action);
    }

    private Booking awaitingPayment() {
        Booking booking = new Booking();
        booking.setId("b-1");
        booking.setRef("BK-20261120-ABCDEF");
        booking.setCustomerId(CUSTOMER);
        booking.setProviderId(PROVIDER);
        booking.setListingId("l-1");
        booking.setStatus(BookingStatus.AWAITING_PAYMENT);
        booking.setAcceptedAt(ACCEPTED);
        booking.setPaymentDueAt(DUE);
        booking.setPriceSnapshot(new BookingPriceSnapshot("Home Cleaning", "cat-1", "FIXED",
                new BigDecimal("5000.00"), List.of(), new BigDecimal("6500.00"), "LKR", new BigDecimal("0.10"),
                90, Instant.parse("2026-11-19T10:00:00Z")));
        when(bookingRepository.findById("b-1")).thenReturn(Optional.of(booking));
        return booking;
    }

    private Booking withLastChange(Booking booking, BookingStatus from, BookingStatus to, String reference) {
        BookingStatusChange change = new BookingStatusChange(from, to, NOW.minusSeconds(60),
                BookingStatusChange.SYSTEM, "x");
        change.setReference(reference);
        booking.getTimeline().add(change);
        booking.setStatus(to);
        return booking;
    }

    private void givenSaveReturnsArgument() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static void assertErrorCode(Throwable e, String code) {
        assertThat(((ApiException) e).getErrorCode()).isEqualTo(code);
    }

    // ---- getPaymentSnapshot ----

    @Test
    @DisplayName("Snapshot for the booking's customer: amount, currency, commission and deadlines from the booking")
    void snapshotForCustomer() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        awaitingPayment();

        BookingPaymentSnapshot snapshot = facade.getPaymentSnapshot("b-1");

        assertThat(snapshot.amount()).isEqualByComparingTo("6500.00");
        assertThat(snapshot.currency()).isEqualTo("LKR");
        assertThat(snapshot.commissionRate()).isEqualByComparingTo("0.10");
        assertThat(snapshot.acceptedAt()).isEqualTo(ACCEPTED);
        assertThat(snapshot.paymentDueAt()).isEqualTo(DUE);
        assertThat(snapshot.status()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(snapshot.bookingRef()).isEqualTo("BK-20261120-ABCDEF");
    }

    @Test
    @DisplayName("Snapshot: another customer or the provider → 403")
    void snapshotForbiddenForOthers() {
        loginAs(PROVIDER, Role.PROVIDER);
        awaitingPayment();

        assertThatThrownBy(() -> facade.getPaymentSnapshot("b-1")).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN"})
    @DisplayName("Snapshot readable by admin roles")
    void snapshotForAdmins(Role role) {
        loginAs("a-1", role);
        awaitingPayment();

        assertThat(facade.getPaymentSnapshot("b-1").amount()).isEqualByComparingTo("6500.00");
    }

    @Test
    @DisplayName("Snapshot readable by the payment module (trusted call)")
    void snapshotForPaymentModule() {
        awaitingPayment();

        assertThat(asPaymentModule(() -> facade.getPaymentSnapshot("b-1")).amount()).isEqualByComparingTo("6500.00");
    }

    @Test
    @DisplayName("Snapshot without login → 401, nothing loaded")
    void snapshotUnauthenticated() {
        assertThatThrownBy(() -> facade.getPaymentSnapshot("b-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertErrorCode(e, "UNAUTHORIZED"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Booking without a price snapshot → 409 PRICE_SNAPSHOT_MISSING")
    void snapshotMissing() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        awaitingPayment().setPriceSnapshot(null);

        assertThatThrownBy(() -> facade.getPaymentSnapshot("b-1"))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "PRICE_SNAPSHOT_MISSING"));
    }

    // ---- markPaymentConfirmed ----

    @Test
    @DisplayName("Confirmed by the payment module: AWAITING_PAYMENT → CONFIRMED as SYSTEM with the payment reference")
    void confirm() {
        Booking booking = awaitingPayment();
        givenSaveReturnsArgument();

        BookingParticipants result = asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF));

        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        BookingStatusChange change = booking.getTimeline().get(0);
        assertThat(change.getChangedBy()).isEqualTo(BookingStatusChange.SYSTEM);
        assertThat(change.getReference()).isEqualTo(PAY_REF);
        assertThat(change.getChangedAt()).isEqualTo(NOW);
        verify(notifier).confirmed(booking);
    }

    @Test
    @DisplayName("Confirm from a signed webhook (unauthenticated HTTP request) inside the trusted call")
    void confirmFromWebhookRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        awaitingPayment();
        givenSaveReturnsArgument();

        assertThat(asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF)).status())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Retry with the same payment reference → unchanged, nothing saved")
    void confirmIdempotent() {
        withLastChange(awaitingPayment(), BookingStatus.AWAITING_PAYMENT, BookingStatus.CONFIRMED, PAY_REF);

        BookingParticipants result = asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF));

        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any(Booking.class));
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("Already confirmed by a different payment → 409 PAYMENT_ALREADY_APPLIED")
    void confirmDifferentReference() {
        withLastChange(awaitingPayment(), BookingStatus.AWAITING_PAYMENT, BookingStatus.CONFIRMED, PAY_REF);

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentConfirmed("b-1", OTHER_REF)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "PAYMENT_ALREADY_APPLIED"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @ParameterizedTest(name = "Same payment retried after {0} → unchanged, nothing saved")
    @EnumSource(value = BookingStatus.class, names = {"IN_PROGRESS", "COMPLETION_PENDING", "COMPLETED", "DISPUTED"})
    @DisplayName("Gateway retry with the same reference after the booking moved past CONFIRMED is idempotent")
    void confirmRetryAfterBookingProgressed(BookingStatus laterStatus) {
        Booking booking = withLastChange(awaitingPayment(), BookingStatus.AWAITING_PAYMENT,
                BookingStatus.CONFIRMED, PAY_REF);
        withLastChange(booking, BookingStatus.CONFIRMED, laterStatus, null);
        int timelineSize = booking.getTimeline().size();

        BookingParticipants result = asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF));

        assertThat(result.status()).isEqualTo(laterStatus);
        assertThat(booking.getTimeline()).hasSize(timelineSize);
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Different payment reference after the booking moved past CONFIRMED → 409 PAYMENT_ALREADY_APPLIED")
    void confirmDifferentReferenceAfterBookingProgressed() {
        Booking booking = withLastChange(awaitingPayment(), BookingStatus.AWAITING_PAYMENT,
                BookingStatus.CONFIRMED, PAY_REF);
        withLastChange(booking, BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, null);
        withLastChange(booking, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING, null);
        withLastChange(booking, BookingStatus.COMPLETION_PENDING, BookingStatus.COMPLETED, null);

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentConfirmed("b-1", OTHER_REF)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "PAYMENT_ALREADY_APPLIED"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Booking already EXPIRED → 409 INVALID_STATUS_TRANSITION (payment module refunds)")
    void confirmAfterExpiry() {
        withLastChange(awaitingPayment(), BookingStatus.AWAITING_PAYMENT, BookingStatus.EXPIRED, null);

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Logged-in user (even the customer) cannot confirm a payment → 403, nothing loaded")
    void confirmByUserForbidden() {
        loginAs(CUSTOMER, Role.CUSTOMER);

        assertThatThrownBy(() -> facade.markPaymentConfirmed("b-1", PAY_REF)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Plain call without the trusted context → 401, nothing loaded")
    void confirmWithoutTrustedContext() {
        assertThatThrownBy(() -> facade.markPaymentConfirmed("b-1", PAY_REF))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertErrorCode(e, "UNAUTHORIZED"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Dispute-settlement trusted context cannot change payment status")
    void wrongPurposeRejected() {
        assertThatThrownBy(() -> TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.DISPUTE_SETTLEMENT,
                () -> facade.markPaymentConfirmed("b-1", PAY_REF)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertErrorCode(e, "UNAUTHORIZED"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Trusted payment call cannot start for a logged-in user")
    void paymentCallRefusedForUser() {
        loginAs(CUSTOMER, Role.CUSTOMER);

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(bookingRepository);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " ", "pi 123", "ref/../x", "a{b}"})
    @DisplayName("Invalid payment reference → 400 INVALID_PAYMENT_REFERENCE")
    void invalidReference(String reference) {
        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentConfirmed("b-1", reference)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertErrorCode(e, "INVALID_PAYMENT_REFERENCE"));
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("Unknown booking → 404")
    void unknownBooking() {
        when(bookingRepository.findById("x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentConfirmed("x", PAY_REF)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Concurrent update → 409 BOOKING_UPDATE_CONFLICT (safe to retry)")
    void concurrentUpdate() {
        awaitingPayment();
        when(bookingRepository.save(any(Booking.class))).thenThrow(new OptimisticLockingFailureException("v"));

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "BOOKING_UPDATE_CONFLICT"));
    }

    // ---- markPaymentExpired ----

    @Test
    @DisplayName("Expired after paymentDueAt + 15 min grace: AWAITING_PAYMENT → EXPIRED as SYSTEM")
    void expire() {
        Booking booking = awaitingPayment();
        booking.setPaymentDueAt(NOW.minus(Duration.ofMinutes(16)));
        givenSaveReturnsArgument();

        BookingParticipants result = asPaymentModule(() -> facade.markPaymentExpired("b-1", PAY_REF));

        assertThat(result.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(booking.getTimeline().get(0).getReference()).isEqualTo(PAY_REF);
        verify(notifier).paymentExpired(booking);
    }

    @Test
    @DisplayName("Before paymentDueAt → 409 PAYMENT_NOT_DUE")
    void expireTooEarly() {
        awaitingPayment().setPaymentDueAt(NOW.plusSeconds(60));

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentExpired("b-1", PAY_REF)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "PAYMENT_NOT_DUE"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @ParameterizedTest(name = "paymentDueAt {0} min before now → 409 PAYMENT_NOT_DUE")
    @ValueSource(ints = {0, 1, 5, 14, 15})
    @DisplayName("During the grace period (up to and including due + 15 min) → 409 PAYMENT_NOT_DUE, nothing saved")
    void expireDuringGrace(int minutesSinceDue) {
        awaitingPayment().setPaymentDueAt(NOW.minus(Duration.ofMinutes(minutesSinceDue)));

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentExpired("b-1", PAY_REF)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "PAYMENT_NOT_DUE"));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @ParameterizedTest(name = "paymentDueAt {0} s before now → facade and expiry job agree")
    @ValueSource(ints = {-60, 0, 300, 899, 900, 901, 3600})
    @DisplayName("Facade and BookingExpiryJob use the same timeout rule (paymentDueAt + grace < now)")
    void facadeMatchesExpiryJobRule(int secondsSinceDue) {
        BookingPaymentPolicy policy = new BookingPaymentPolicy(15, 15);
        Instant due = NOW.minusSeconds(secondsSinceDue);
        boolean jobWouldExpire = due.isBefore(policy.expireDueBefore(NOW));

        assertThat(policy.isPaymentTimedOut(due, NOW)).isEqualTo(jobWouldExpire);
        assertThat(jobWouldExpire).isEqualTo(secondsSinceDue > 900);
    }

    @Test
    @DisplayName("Payment success during the grace period (after paymentDueAt) is still confirmed")
    void confirmDuringGrace() {
        Booking booking = awaitingPayment();
        booking.setPaymentDueAt(NOW.minus(Duration.ofMinutes(5)));
        givenSaveReturnsArgument();

        BookingParticipants result = asPaymentModule(() -> facade.markPaymentConfirmed("b-1", PAY_REF));

        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Already expired (e.g. by the booking expiry job) → returned unchanged")
    void expireIdempotent() {
        withLastChange(awaitingPayment(), BookingStatus.AWAITING_PAYMENT, BookingStatus.EXPIRED, null);

        assertThat(asPaymentModule(() -> facade.markPaymentExpired("b-1", PAY_REF)).status())
                .isEqualTo(BookingStatus.EXPIRED);
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Already CONFIRMED → 409 INVALID_STATUS_TRANSITION")
    void expireConfirmed() {
        withLastChange(awaitingPayment(), BookingStatus.AWAITING_PAYMENT, BookingStatus.CONFIRMED, PAY_REF);

        assertThatThrownBy(() -> asPaymentModule(() -> facade.markPaymentExpired("b-1", PAY_REF)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertErrorCode(e, "INVALID_STATUS_TRANSITION"));
    }
}
