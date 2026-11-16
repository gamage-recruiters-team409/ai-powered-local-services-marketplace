package com.gamagerecruiters.marketplace.modules.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;

class BookingTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:00:00Z");

    private static Booking pendingBooking() {
        Booking booking = new Booking();
        booking.setId("b-1");
        booking.setStatus(BookingStatus.PENDING_PROVIDER);
        return booking;
    }

    @Test
    @DisplayName("Allowed status change updates status, updatedAt and adds a timeline entry")
    void allowedChangeAddsTimelineEntry() {
        Booking booking = pendingBooking();

        booking.changeStatus(BookingStatus.AWAITING_PAYMENT, "provider-1", null, NOW);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(booking.getUpdatedAt()).isEqualTo(NOW);
        assertThat(booking.getTimeline()).hasSize(1);
        BookingStatusChange change = booking.getTimeline().get(0);
        assertThat(change.getFromStatus()).isEqualTo(BookingStatus.PENDING_PROVIDER);
        assertThat(change.getToStatus()).isEqualTo(BookingStatus.AWAITING_PAYMENT);
        assertThat(change.getChangedBy()).isEqualTo("provider-1");
        assertThat(change.getChangedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("System changes are recorded with SYSTEM as the actor")
    void systemChangeRecordsSystemActor() {
        Booking booking = pendingBooking();

        booking.changeStatus(BookingStatus.EXPIRED, BookingStatusChange.SYSTEM, "Response deadline passed", NOW);

        assertThat(booking.getTimeline().get(0).getChangedBy()).isEqualTo("SYSTEM");
        assertThat(booking.getTimeline().get(0).getReason()).isEqualTo("Response deadline passed");
    }

    @Test
    @DisplayName("Not allowed status change is rejected with 409 and changes nothing")
    void notAllowedChangeIsRejected() {
        Booking booking = pendingBooking();

        assertThatThrownBy(() -> booking.changeStatus(BookingStatus.CONFIRMED, "provider-1", null, NOW))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ResourceConflictException) e).getErrorCode())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PROVIDER);
        assertThat(booking.getTimeline()).isEmpty();
        assertThat(booking.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("A booking without a status cannot change status")
    void nullStatusIsRejected() {
        Booking booking = new Booking();

        assertThatThrownBy(() -> booking.changeStatus(BookingStatus.CONFIRMED, "x", null, NOW))
                .isInstanceOf(ResourceConflictException.class);
    }
}
