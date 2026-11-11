package com.gamagerecruiters.marketplace.modules.booking.domain;

import static com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.AWAITING_PAYMENT;
import static com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.COMPLETED;
import static com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.COMPLETION_PENDING;
import static com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.CONFIRMED;
import static com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.DISPUTED;
import static com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.IN_PROGRESS;
import static com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus.PENDING_PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class BookingStatusTest {

    @Test
    @DisplayName("Has exactly the 13 approved statuses")
    void hasApprovedStatuses() {
        assertThat(BookingStatus.values()).hasSize(13);
    }

    @Test
    @DisplayName("Open statuses are exactly the six agreed with the customer module")
    void openStatusesMatchAgreedContract() {
        assertThat(BookingStatus.OPEN_STATUSES).containsExactlyInAnyOrder(
                PENDING_PROVIDER, AWAITING_PAYMENT, CONFIRMED, IN_PROGRESS, COMPLETION_PENDING, DISPUTED);
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "PENDING_PROVIDER, AWAITING_PAYMENT",
            "PENDING_PROVIDER, DECLINED",
            "PENDING_PROVIDER, EXPIRED",
            "PENDING_PROVIDER, CANCELLED_BY_CUSTOMER",
            "AWAITING_PAYMENT, CONFIRMED",
            "AWAITING_PAYMENT, EXPIRED",
            "AWAITING_PAYMENT, CANCELLED_BY_CUSTOMER",
            "CONFIRMED, IN_PROGRESS",
            "CONFIRMED, CANCELLED_BY_CUSTOMER",
            "CONFIRMED, CANCELLED_BY_PROVIDER",
            "CONFIRMED, DISPUTED",
            "IN_PROGRESS, COMPLETION_PENDING",
            "IN_PROGRESS, CANCELLED_BY_CUSTOMER",
            "IN_PROGRESS, CANCELLED_BY_PROVIDER",
            "IN_PROGRESS, DISPUTED",
            "COMPLETION_PENDING, COMPLETED",
            "COMPLETION_PENDING, DISPUTED",
            "COMPLETED, DISPUTED",
            "DISPUTED, COMPLETED",
            "DISPUTED, REFUNDED",
            "DISPUTED, PARTIALLY_REFUNDED"
    })
    void allowedTransitions(BookingStatus from, BookingStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is not allowed")
    @CsvSource({
            "PENDING_PROVIDER, CONFIRMED",
            "PENDING_PROVIDER, CANCELLED_BY_PROVIDER",
            "AWAITING_PAYMENT, IN_PROGRESS",
            "CONFIRMED, COMPLETED",
            "CONFIRMED, PENDING_PROVIDER",
            "IN_PROGRESS, COMPLETED",
            "COMPLETION_PENDING, CANCELLED_BY_CUSTOMER",
            "COMPLETED, REFUNDED",
            "DISPUTED, CANCELLED_BY_CUSTOMER"
    })
    void notAllowedTransitions(BookingStatus from, BookingStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest(name = "{0} is final")
    @EnumSource(value = BookingStatus.class,
            names = {"DECLINED", "EXPIRED", "CANCELLED_BY_CUSTOMER", "CANCELLED_BY_PROVIDER", "REFUNDED", "PARTIALLY_REFUNDED"})
    void finalStatusesHaveNoNextStatus(BookingStatus status) {
        assertThat(status.isFinal()).isTrue();
        assertThat(status.allowedNextStatuses()).isEmpty();
        assertThat(status.isOpen()).isFalse();
    }

    @Test
    @DisplayName("No status can move to itself or to null")
    void noSelfOrNullTransition() {
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
            assertThat(status.canTransitionTo(null)).isFalse();
        }
    }

    @Test
    @DisplayName("COMPLETED is not open and not final (a dispute can still be opened)")
    void completedIsClosedButNotFinal() {
        assertThat(COMPLETED.isOpen()).isFalse();
        assertThat(COMPLETED.isFinal()).isFalse();
        assertThat(COMPLETED.allowedNextStatuses()).isEqualTo(EnumSet.of(DISPUTED));
    }
}
