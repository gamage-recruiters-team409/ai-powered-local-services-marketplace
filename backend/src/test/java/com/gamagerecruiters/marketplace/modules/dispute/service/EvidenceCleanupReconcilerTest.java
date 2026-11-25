package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceCleanupTask;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;

/**
 * Reconciliation of durable evidence-cleanup tasks (dispute finding F6) with a mocked store and storage.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceCleanupReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration BACKOFF = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private EvidenceCleanupStore cleanupStore;
    @Mock
    private EvidenceStoragePort storage;

    private EvidenceCleanupReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new EvidenceCleanupReconciler(cleanupStore, storage, Clock.fixed(NOW, ZoneOffset.UTC),
                true, LEASE, BACKOFF, MAX_ATTEMPTS, 10);
    }

    private static EvidenceCleanupTask task(String id, String publicId, int attempts) {
        EvidenceCleanupTask task = new EvidenceCleanupTask();
        task.setId(id);
        task.setPublicId(publicId);
        task.setDisputeId("dispute-" + id);
        task.setEvidenceId("ev-" + id);
        task.setReason(EvidenceCleanupTask.Reason.DELETE_FAILED);
        task.setAttempts(attempts);
        task.setLeaseOwner("lease-" + id);
        return task;
    }

    private void claims(EvidenceCleanupTask... tasks) {
        var stubbing = when(cleanupStore.claimDue(NOW, LEASE));
        for (EvidenceCleanupTask task : tasks) {
            stubbing = stubbing.thenReturn(Optional.of(task));
        }
        stubbing.thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("asset still referenced by a committed evidence record → kept, never deleted (the write did commit)")
    void keepsReferencedAsset() {
        claims(task("t1", "disputes/d1/asset-1", 0));
        when(cleanupStore.isReferenced("disputes/d1/asset-1")).thenReturn(true);
        when(cleanupStore.resolve("t1", "lease-t1")).thenReturn(true);

        assertThat(reconciler.reconcile()).isEqualTo(new EvidenceCleanupReconciler.Result(1, 0, 0, 0, 0));
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("unreferenced orphan → deleted and the task removed")
    void deletesUnreferencedOrphan() {
        claims(task("t1", "disputes/d1/asset-1", 0));
        when(cleanupStore.isReferenced("disputes/d1/asset-1")).thenReturn(false);
        when(cleanupStore.resolve("t1", "lease-t1")).thenReturn(true);

        assertThat(reconciler.reconcile()).isEqualTo(new EvidenceCleanupReconciler.Result(0, 1, 0, 0, 0));
        verify(storage).delete("disputes/d1/asset-1");
        verify(cleanupStore).resolve("t1", "lease-t1");
    }

    @Test
    @DisplayName("deletion fails below the attempt budget → rescheduled with backoff, not removed")
    void reschedulesOnFailureBelowBudget() {
        claims(task("t1", "disputes/d1/asset-1", 0));
        when(cleanupStore.isReferenced("disputes/d1/asset-1")).thenReturn(false);
        doThrow(new IllegalStateException("provider down")).when(storage).delete("disputes/d1/asset-1");

        assertThat(reconciler.reconcile()).isEqualTo(new EvidenceCleanupReconciler.Result(0, 0, 1, 0, 0));
        verify(cleanupStore).reschedule("t1", "lease-t1", NOW.plus(BACKOFF));
        verify(cleanupStore, never()).resolve(anyString(), anyString());
        verify(cleanupStore, never()).markExhausted(anyString(), anyString());
    }

    @Test
    @DisplayName("deletion fails on the final allowed attempt → marked exhausted for manual recovery, not retried")
    void exhaustsAtAttemptBudget() {
        claims(task("t1", "disputes/d1/asset-1", MAX_ATTEMPTS - 1));
        when(cleanupStore.isReferenced("disputes/d1/asset-1")).thenReturn(false);
        doThrow(new IllegalStateException("provider down")).when(storage).delete("disputes/d1/asset-1");

        assertThat(reconciler.reconcile()).isEqualTo(new EvidenceCleanupReconciler.Result(0, 0, 0, 1, 0));
        verify(cleanupStore).markExhausted("t1", "lease-t1");
        verify(cleanupStore, never()).reschedule(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("another instance resolved the task first → counted as skipped")
    void raceOnResolve() {
        claims(task("t1", "disputes/d1/asset-1", 0));
        when(cleanupStore.isReferenced("disputes/d1/asset-1")).thenReturn(false);
        when(cleanupStore.resolve("t1", "lease-t1")).thenReturn(false);

        assertThat(reconciler.reconcile()).isEqualTo(new EvidenceCleanupReconciler.Result(0, 0, 0, 0, 1));
    }

    @Test
    @DisplayName("a run processes at most batch-size tasks")
    void respectsBatchSize() {
        reconciler = new EvidenceCleanupReconciler(cleanupStore, storage, Clock.fixed(NOW, ZoneOffset.UTC),
                true, LEASE, BACKOFF, MAX_ATTEMPTS, 2);
        when(cleanupStore.claimDue(any(), any())).thenReturn(Optional.of(task("t1", "disputes/d1/asset-1", 0)));
        when(cleanupStore.isReferenced(anyString())).thenReturn(true);
        when(cleanupStore.resolve(eq("t1"), anyString())).thenReturn(true);

        reconciler.reconcile();

        verify(cleanupStore, times(2)).claimDue(any(), any());
    }

    @Test
    @DisplayName("scheduled run does nothing when switched off")
    void disabledDoesNothing() {
        reconciler = new EvidenceCleanupReconciler(cleanupStore, storage, Clock.fixed(NOW, ZoneOffset.UTC),
                false, LEASE, BACKOFF, MAX_ATTEMPTS, 10);

        reconciler.scheduledRun();

        verify(cleanupStore, never()).claimDue(any(), any());
    }

    @Test
    @DisplayName("invalid settings are rejected at construction")
    void invalidSettingsRejected() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new EvidenceCleanupReconciler(cleanupStore, storage, clock, true,
                Duration.ZERO, BACKOFF, MAX_ATTEMPTS, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceCleanupReconciler(cleanupStore, storage, clock, true,
                LEASE, BACKOFF, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceCleanupReconciler(cleanupStore, storage, clock, true,
                LEASE, BACKOFF, MAX_ATTEMPTS, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
