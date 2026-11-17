package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeBookingSyncReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Duration MIN_AGE = Duration.ofMinutes(2);
    private static final Duration DELETE_AFTER = Duration.ofMinutes(15);
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Mock
    private DisputeBookingSyncStore syncStore;
    @Mock
    private BookingLookupPort bookingLookupPort;

    private DisputeBookingSyncReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new DisputeBookingSyncReconciler(syncStore, bookingLookupPort, Clock.fixed(NOW, ZoneOffset.UTC),
                true, MIN_AGE, DELETE_AFTER, LEASE, 10);
    }

    private static Dispute pending(String id, Duration age) {
        Dispute dispute = new Dispute();
        dispute.setId(id);
        dispute.setBookingId("booking-" + id);
        dispute.setBookingSyncState(BookingSyncState.PENDING);
        dispute.setCreatedAt(NOW.minus(age));
        return dispute;
    }

    private void claims(Dispute... disputes) {
        var stubbing = when(syncStore.claimPending(NOW.minus(MIN_AGE), NOW, LEASE));
        for (Dispute dispute : disputes) {
            stubbing = stubbing.thenReturn(Optional.of(dispute));
        }
        stubbing.thenReturn(Optional.empty());
    }

    private void booking(String bookingId, boolean disputed) {
        when(bookingLookupPort.getBooking(bookingId))
                .thenReturn(new BookingSnapshot(bookingId, "c-1", "p-1", disputed));
    }

    @Test
    @DisplayName("booking DISPUTED -> the intent record is marked SYNCED")
    void completesWhenBookingDisputed() {
        claims(pending("d1", Duration.ofMinutes(3)));
        booking("booking-d1", true);
        when(syncStore.markSynced("d1")).thenReturn(Optional.of(pending("d1", Duration.ZERO)));

        assertThat(reconciler.reconcile()).isEqualTo(new DisputeBookingSyncReconciler.Result(1, 0, 0));
        verify(syncStore, never()).deleteIfPending(anyString());
    }

    @Test
    @DisplayName("booking not DISPUTED and older than delete-after -> the intent record is removed")
    void removesOldIntentWhenBookingNotDisputed() {
        claims(pending("d1", Duration.ofMinutes(20)));
        booking("booking-d1", false);
        when(syncStore.deleteIfPending("d1")).thenReturn(true);

        assertThat(reconciler.reconcile()).isEqualTo(new DisputeBookingSyncReconciler.Result(0, 1, 0));
        verify(syncStore, never()).markSynced(anyString());
    }

    @Test
    @DisplayName("booking not DISPUTED but younger than delete-after -> left for a later run")
    void keepsYoungIntent() {
        claims(pending("d1", Duration.ofMinutes(5)));
        booking("booking-d1", false);

        assertThat(reconciler.reconcile()).isEqualTo(new DisputeBookingSyncReconciler.Result(0, 0, 1));
        verify(syncStore, never()).deleteIfPending(anyString());
        verify(syncStore, never()).markSynced(anyString());
    }

    @Test
    @DisplayName("booking no longer exists and the record is old -> removed")
    void removesWhenBookingMissing() {
        claims(pending("d1", Duration.ofMinutes(30)));
        when(bookingLookupPort.getBooking("booking-d1"))
                .thenThrow(new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
        when(syncStore.deleteIfPending("d1")).thenReturn(true);

        assertThat(reconciler.reconcile().deleted()).isEqualTo(1);
    }

    @Test
    @DisplayName("booking lookup fails -> skipped (lease expires), the run continues with the next record")
    void lookupFailureSkipsRecord() {
        claims(pending("d1", Duration.ofMinutes(30)), pending("d2", Duration.ofMinutes(3)));
        when(bookingLookupPort.getBooking("booking-d1")).thenThrow(new DataAccessResourceFailureException("down"));
        booking("booking-d2", true);
        when(syncStore.markSynced("d2")).thenReturn(Optional.of(pending("d2", Duration.ZERO)));

        assertThat(reconciler.reconcile()).isEqualTo(new DisputeBookingSyncReconciler.Result(1, 0, 1));
        verify(syncStore, never()).deleteIfPending("d1");
    }

    @Test
    @DisplayName("another instance completed the record first -> counted as skipped, nothing else changes")
    void raceWithAnotherInstance() {
        claims(pending("d1", Duration.ofMinutes(3)));
        booking("booking-d1", true);
        when(syncStore.markSynced("d1")).thenReturn(Optional.empty());

        assertThat(reconciler.reconcile()).isEqualTo(new DisputeBookingSyncReconciler.Result(0, 0, 1));
    }

    @Test
    @DisplayName("a run processes at most batch-size records")
    void respectsBatchSize() {
        reconciler = new DisputeBookingSyncReconciler(syncStore, bookingLookupPort, Clock.fixed(NOW, ZoneOffset.UTC),
                true, MIN_AGE, DELETE_AFTER, LEASE, 2);
        when(syncStore.claimPending(any(), any(), any())).thenReturn(Optional.of(pending("d1", Duration.ofMinutes(3))));
        booking("booking-d1", false);

        reconciler.reconcile();

        verify(syncStore, times(2)).claimPending(any(), any(), any());
    }

    @Test
    @DisplayName("scheduled run does nothing when switched off")
    void disabledDoesNothing() {
        reconciler = new DisputeBookingSyncReconciler(syncStore, bookingLookupPort, Clock.fixed(NOW, ZoneOffset.UTC),
                false, MIN_AGE, DELETE_AFTER, LEASE, 10);

        reconciler.scheduledRun();

        verify(syncStore, never()).claimPending(any(), any(), any());
    }

    @Test
    @DisplayName("invalid settings are rejected at startup")
    void invalidSettingsRejected() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new DisputeBookingSyncReconciler(syncStore, bookingLookupPort, clock, true,
                Duration.ofMinutes(20), Duration.ofMinutes(15), LEASE, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DisputeBookingSyncReconciler(syncStore, bookingLookupPort, clock, true,
                MIN_AGE, DELETE_AFTER, Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DisputeBookingSyncReconciler(syncStore, bookingLookupPort, clock, true,
                MIN_AGE, DELETE_AFTER, LEASE, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
