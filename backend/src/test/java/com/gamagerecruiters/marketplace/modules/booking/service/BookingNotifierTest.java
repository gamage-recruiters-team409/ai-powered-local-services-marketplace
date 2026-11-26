package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddress;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingDeclineReason;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationReason;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationEvent;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationService;

@ExtendWith(MockitoExtension.class)
class BookingNotifierTest {

    private static final Instant SLOT = Instant.parse("2026-12-01T04:30:00Z");

    @Mock
    private NotificationService notificationService;

    private BookingNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new BookingNotifier(notificationService);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Booking booking(BookingStatus status) {
        Booking b = new Booking();
        b.setId("b-1");
        b.setRef("BK-20261120-ABCDEF");
        b.setCustomerId("cust-1");
        b.setProviderId("prov-1");
        b.setSlotStart(SLOT);
        b.setStatus(status);
        b.setAddress(new BookingAddress("12 Lake Rd", "Apt 3", "Kandy", "Kandy", "20000"));
        b.setNotes("Gate code 1234");
        return b;
    }

    private List<NotificationEvent> sent(int count) {
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, times(count)).notify(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("New request → provider; data uses only WP06 keys, district only (no street, notes)")
    void requestReceived() {
        notifier.requestReceived(booking(BookingStatus.PENDING_PROVIDER));

        NotificationEvent event = sent(1).get(0);
        assertThat(event.type()).isEqualTo(NotificationType.BOOKING_REQUEST_RECEIVED);
        assertThat(event.recipientUserId()).isEqualTo("prov-1");
        assertThat(event.bookingId()).isEqualTo("b-1");
        assertThat(event.data()).containsOnlyKeys("reference", "slotStart", "bookingStatus", "district");
        assertThat(event.data()).containsEntry("reference", "BK-20261120-ABCDEF")
                .containsEntry("slotStart", "2026-12-01T04:30:00Z")
                .containsEntry("bookingStatus", "PENDING_PROVIDER")
                .containsEntry("district", "Kandy");
        assertThat(event.data().values()).doesNotContain("12 Lake Rd", "Gate code 1234", "20000");
    }

    @Test
    @DisplayName("Accepted → customer")
    void accepted() {
        notifier.accepted(booking(BookingStatus.AWAITING_PAYMENT));

        NotificationEvent event = sent(1).get(0);
        assertThat(event.type()).isEqualTo(NotificationType.BOOKING_ACCEPTED);
        assertThat(event.recipientUserId()).isEqualTo("cust-1");
        assertThat(event.data()).doesNotContainKey("reason");
    }

    @ParameterizedTest(name = "{0} → reason {1}")
    @CsvSource({"NOT_AVAILABLE, PROVIDER_UNAVAILABLE", "TOO_SHORT_NOTICE, PROVIDER_UNAVAILABLE",
            "OUTSIDE_SERVICE_AREA, OUTSIDE_SERVICE_AREA", "JOB_NOT_SUITABLE, OTHER", "OTHER, OTHER"})
    @DisplayName("Declined → customer, decline reason mapped to an allowed WP06 reason code")
    void declined(BookingDeclineReason declineReason, NotificationReason expected) {
        notifier.declined(booking(BookingStatus.DECLINED), declineReason);

        NotificationEvent event = sent(1).get(0);
        assertThat(event.type()).isEqualTo(NotificationType.BOOKING_DECLINED);
        assertThat(event.recipientUserId()).isEqualTo("cust-1");
        assertThat(event.data()).containsEntry("reason", expected.name());
    }

    @Test
    @DisplayName("Request expired → customer BOOKING_EXPIRED and provider BOOKING_AUTO_DECLINED, reason NO_RESPONSE")
    void requestExpired() {
        notifier.requestExpired(booking(BookingStatus.EXPIRED));

        List<NotificationEvent> events = sent(2);
        assertThat(events).extracting(NotificationEvent::type)
                .containsExactly(NotificationType.BOOKING_EXPIRED, NotificationType.BOOKING_AUTO_DECLINED);
        assertThat(events).extracting(NotificationEvent::recipientUserId).containsExactly("cust-1", "prov-1");
        assertThat(events).allSatisfy(e -> assertThat(e.data()).containsEntry("reason", "NO_RESPONSE"));
    }

    @Test
    @DisplayName("Payment expired → both parties, reason PAYMENT_ISSUE")
    void paymentExpired() {
        notifier.paymentExpired(booking(BookingStatus.EXPIRED));

        List<NotificationEvent> events = sent(2);
        assertThat(events).extracting(NotificationEvent::type).containsOnly(NotificationType.BOOKING_EXPIRED);
        assertThat(events).extracting(NotificationEvent::recipientUserId).containsExactly("cust-1", "prov-1");
        assertThat(events).allSatisfy(e -> assertThat(e.data()).containsEntry("reason", "PAYMENT_ISSUE"));
    }

    @Test
    @DisplayName("Confirmed → both parties")
    void confirmed() {
        notifier.confirmed(booking(BookingStatus.CONFIRMED));

        List<NotificationEvent> events = sent(2);
        assertThat(events).extracting(NotificationEvent::type).containsOnly(NotificationType.BOOKING_CONFIRMED);
        assertThat(events).extracting(NotificationEvent::recipientUserId).containsExactly("cust-1", "prov-1");
    }

    @Test
    @DisplayName("Inside a transaction: sent only after commit, never on rollback")
    void waitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        notifier.accepted(booking(BookingStatus.AWAITING_PAYMENT));
        verifyNoInteractions(notificationService);

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);
        registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(notificationService);

        registered.forEach(TransactionSynchronization::afterCommit);
        assertThat(sent(1).get(0).type()).isEqualTo(NotificationType.BOOKING_ACCEPTED);
    }

    @Test
    @DisplayName("A notification failure never breaks the booking action")
    void failureIsSwallowed() {
        doThrow(new IllegalStateException("down")).when(notificationService).notify(any(NotificationEvent.class));

        assertThatCode(() -> notifier.confirmed(booking(BookingStatus.CONFIRMED))).doesNotThrowAnyException();
        sent(2);
    }

    @Test
    @DisplayName("No address or ref (old data) → still sends with the keys it has")
    void missingOptionalData() {
        Booking b = booking(BookingStatus.CONFIRMED);
        b.setAddress(null);
        b.setRef(null);

        notifier.accepted(b);

        assertThat(sent(1).get(0).data()).containsOnlyKeys("slotStart", "bookingStatus");
    }
}
