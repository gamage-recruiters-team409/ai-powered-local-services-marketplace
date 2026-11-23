package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BookingDurationPolicyTest {

    @ParameterizedTest(name = "{0} service minutes → {1} scheduled minutes")
    @CsvSource({"1, 15", "10, 15", "15, 15", "16, 30", "60, 60", "70, 75", "100, 105", "479, 480", "480, 480"})
    @DisplayName("Service minutes are rounded up to the 15-minute grid")
    void roundsUpToGrid(int service, int scheduled) {
        assertThat(BookingDurationPolicy.toScheduledMinutes(service)).isEqualTo(scheduled);
        assertThat(BookingDurationPolicy.isSchedulable(service)).isTrue();
    }

    @ParameterizedTest(name = "{0} minutes is not schedulable")
    @ValueSource(ints = {0, -10, 481, 495, 1440, 6240})
    @DisplayName("Zero, negative and longer than one working day (480) are not schedulable")
    void notSchedulable(int service) {
        assertThat(BookingDurationPolicy.isSchedulable(service)).isFalse();
    }

    @Test
    @DisplayName("toScheduledMinutes rejects non-positive input")
    void rejectsNonPositive() {
        assertThatThrownBy(() -> BookingDurationPolicy.toScheduledMinutes(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
