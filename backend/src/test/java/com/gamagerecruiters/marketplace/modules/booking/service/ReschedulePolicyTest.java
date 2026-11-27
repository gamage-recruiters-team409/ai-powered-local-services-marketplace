package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReschedulePolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private final ReschedulePolicy policy = new ReschedulePolicy(2, 6, 12);

    @Test
    @DisplayName("Remaining moves: 2, 1, 0 and never negative")
    void remaining() {
        assertThat(policy.remaining(0)).isEqualTo(2);
        assertThat(policy.remaining(1)).isEqualTo(1);
        assertThat(policy.remaining(2)).isZero();
        assertThat(policy.remaining(5)).isZero();
    }

    @Test
    @DisplayName("6 h rule: exactly 6 h before the start is still allowed, 1 s later is too late")
    void tooLateBoundary() {
        Instant start = NOW.plus(Duration.ofHours(6));
        assertThat(policy.isTooLate(start, NOW)).isFalse();
        assertThat(policy.isTooLate(start, NOW.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("Answer deadline: 12 h, or the current start when that is sooner")
    void expiresAt() {
        assertThat(policy.expiresAt(NOW, NOW.plus(Duration.ofDays(2)))).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(policy.expiresAt(NOW, NOW.plus(Duration.ofHours(7)))).isEqualTo(NOW.plus(Duration.ofHours(7)));
    }
}
