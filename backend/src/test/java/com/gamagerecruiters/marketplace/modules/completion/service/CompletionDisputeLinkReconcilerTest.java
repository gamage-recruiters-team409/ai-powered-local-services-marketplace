package com.gamagerecruiters.marketplace.modules.completion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Backoff used by the dispute-link reconciler for records it cannot link yet. */
class CompletionDisputeLinkReconcilerTest {

    private static final Duration BASE = Duration.ofMinutes(10);
    private static final Duration MAX = Duration.ofHours(6);

    @Test
    @DisplayName("Retry delay doubles per previous attempt: 10, 20, 40, 80 minutes")
    void delayDoubles() {
        assertEquals(Duration.ofMinutes(10), CompletionDisputeLinkReconciler.retryDelay(0, BASE, MAX));
        assertEquals(Duration.ofMinutes(20), CompletionDisputeLinkReconciler.retryDelay(1, BASE, MAX));
        assertEquals(Duration.ofMinutes(40), CompletionDisputeLinkReconciler.retryDelay(2, BASE, MAX));
        assertEquals(Duration.ofMinutes(80), CompletionDisputeLinkReconciler.retryDelay(3, BASE, MAX));
    }

    @Test
    @DisplayName("Retry delay never exceeds the maximum, also for very many attempts or bad input")
    void delayIsCapped() {
        assertEquals(MAX, CompletionDisputeLinkReconciler.retryDelay(6, BASE, MAX));
        assertEquals(MAX, CompletionDisputeLinkReconciler.retryDelay(1_000, BASE, MAX));
        assertEquals(BASE, CompletionDisputeLinkReconciler.retryDelay(-3, BASE, MAX));
    }
}
