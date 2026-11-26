package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceCleanupTask;
import com.gamagerecruiters.marketplace.modules.dispute.port.EvidenceStoragePort;

/**
 * Unit and lifecycle tests for {@link EvidenceCleanupStore}, covering durable fallback buffering when MongoDB task
 * insertion fails (finding F6-C), discovery on recovery, and operator intervention.
 */
class EvidenceCleanupStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String DISPUTE_ID = "dispute-1";
    private static final String EVIDENCE_ID = "ev-1";
    private static final String PUBLIC_ID = "disputes/dispute-1/asset-1";

    private MongoTemplate mongoTemplate;
    private EvidenceCleanupStore store;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        store = new EvidenceCleanupStore(mongoTemplate);
    }

    @Test
    @DisplayName("enqueue succeeds normally when MongoDB is available")
    void enqueueNormalSuccess() {
        store.enqueue(DISPUTE_ID, EVIDENCE_ID, PUBLIC_ID, EvidenceCleanupTask.Reason.UNCERTAIN_WRITE, NOW);

        verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(EvidenceCleanupTask.class));
        assertThat(store.getFallbackCount()).isZero();
    }

    @Test
    @DisplayName("enqueue throws on MongoDB outage but retains task in fallback buffer; drained once DB is restored")
    void enqueueThrowsRetainsInFallbackAndDrainsOnRecovery() {
        // 1. Simulate MongoDB outage: upsert throws
        when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(EvidenceCleanupTask.class)))
                .thenThrow(new RuntimeException("MongoDB connection refused"));

        assertThatThrownBy(() -> store.enqueue(DISPUTE_ID, EVIDENCE_ID, PUBLIC_ID,
                EvidenceCleanupTask.Reason.UNCERTAIN_WRITE, NOW))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MongoDB connection refused");

        // 2. Task is NOT lost: discoverable in fallback buffer
        assertThat(store.getFallbackCount()).isEqualTo(1);
        List<EvidenceCleanupTask> fallback = store.getFallbackTasks();
        assertThat(fallback).singleElement().satisfies(t -> {
            assertThat(t.getPublicId()).isEqualTo(PUBLIC_ID);
            assertThat(t.getDisputeId()).isEqualTo(DISPUTE_ID);
            assertThat(t.getEvidenceId()).isEqualTo(EVIDENCE_ID);
            assertThat(t.getReason()).isEqualTo(EvidenceCleanupTask.Reason.UNCERTAIN_WRITE);
        });

        // 3. Duplicate enqueue for same publicId does not duplicate fallback entry
        store.enqueueFallback(DISPUTE_ID, EVIDENCE_ID, PUBLIC_ID, EvidenceCleanupTask.Reason.UNCERTAIN_WRITE, NOW);
        assertThat(store.getFallbackCount()).isEqualTo(1);

        // 4. Restore database availability: upsert now succeeds
        org.mockito.Mockito.reset(mongoTemplate);

        // 5. drainFallback flushes the retained task into MongoDB
        int drained = store.drainFallback();
        assertThat(drained).isEqualTo(1);
        assertThat(store.getFallbackCount()).isZero();
        verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(EvidenceCleanupTask.class));

        // 6. Repeated drain does not perform duplicate upserts
        assertThat(store.drainFallback()).isZero();
    }

    @Test
    @DisplayName("claimDue automatically drains fallback buffer before claiming tasks")
    void claimDueDrainsFallbackBeforeClaiming() {
        store.enqueueFallback(DISPUTE_ID, EVIDENCE_ID, PUBLIC_ID, EvidenceCleanupTask.Reason.DELETE_FAILED, NOW);
        assertThat(store.getFallbackCount()).isEqualTo(1);

        EvidenceCleanupTask claimedTask = new EvidenceCleanupTask();
        claimedTask.setId("task-1");
        claimedTask.setPublicId(PUBLIC_ID);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(EvidenceCleanupTask.class)))
                .thenReturn(claimedTask);

        Optional<EvidenceCleanupTask> result = store.claimDue(NOW, Duration.ofMinutes(2));

        assertThat(result).isPresent().contains(claimedTask);
        // Fallback buffer was drained before claim
        assertThat(store.getFallbackCount()).isZero();
        verify(mongoTemplate).upsert(any(Query.class), any(Update.class), eq(EvidenceCleanupTask.class));
    }

    @Test
    @DisplayName("drainFallback stops and preserves remaining tasks if MongoDB is still unavailable")
    void drainFallbackPreservesTasksIfDatabaseStillDown() {
        store.enqueueFallback(DISPUTE_ID, EVIDENCE_ID, PUBLIC_ID, EvidenceCleanupTask.Reason.DELETE_FAILED, NOW);
        when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(EvidenceCleanupTask.class)))
                .thenThrow(new RuntimeException("DB still down"));

        int drained = store.drainFallback();

        assertThat(drained).isZero();
        assertThat(store.getFallbackCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("operator actions: findExhausted and retryExhausted")
    void operatorActions() {
        EvidenceCleanupTask exhaustedTask = new EvidenceCleanupTask();
        exhaustedTask.setId("task-exhausted");
        exhaustedTask.setPublicId("disputes/d1/exhausted");
        exhaustedTask.setExhausted(true);

        when(mongoTemplate.find(any(Query.class), eq(EvidenceCleanupTask.class)))
                .thenReturn(List.of(exhaustedTask));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(EvidenceCleanupTask.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        List<EvidenceCleanupTask> exhausted = store.findExhausted();
        assertThat(exhausted).containsExactly(exhaustedTask);

        boolean retried = store.retryExhausted("task-exhausted", NOW);
        assertThat(retried).isTrue();
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(EvidenceCleanupTask.class));
    }

    @Test
    @DisplayName("F6-C end-to-end recovery: asset in fallback buffer is reconciled once DB is restored")
    void endToEndRecoveryFromFallback() {
        // Enqueue into fallback (simulating DB was down during upload failure)
        store.enqueueFallback(DISPUTE_ID, EVIDENCE_ID, PUBLIC_ID, EvidenceCleanupTask.Reason.UNCERTAIN_WRITE, NOW);
        assertThat(store.getFallbackCount()).isEqualTo(1);

        EvidenceStoragePort storage = mock(EvidenceStoragePort.class);
        EvidenceCleanupReconciler reconciler = new EvidenceCleanupReconciler(
                store, storage, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                true, Duration.ofMinutes(2), Duration.ofMinutes(5), 3, 10, Duration.ofMinutes(30), 100);

        // Database is restored:
        // 1. drainFallback will upsert into MongoDB
        // 2. claimDue will return the task
        EvidenceCleanupTask task = new EvidenceCleanupTask();
        task.setId("task-1");
        task.setPublicId(PUBLIC_ID);
        task.setDisputeId(DISPUTE_ID);
        task.setEvidenceId(EVIDENCE_ID);
        task.setLeaseOwner("owner-1");

        when(mongoTemplate.findAndModify(any(), any(), any(), eq(EvidenceCleanupTask.class)))
                .thenReturn(task)
                .thenReturn(null);
        // Dispute does NOT reference the asset (orphan)
        when(mongoTemplate.exists(any(Query.class), eq(com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute.class)))
                .thenReturn(false);
        when(mongoTemplate.remove(any(Query.class), eq(EvidenceCleanupTask.class)))
                .thenReturn(com.mongodb.client.result.DeleteResult.acknowledged(1));

        // Reconcile: drains fallback, verifies not referenced, deletes orphan from storage
        EvidenceCleanupReconciler.Result result = reconciler.reconcile();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.kept()).isZero();
        verify(storage).delete(PUBLIC_ID);
        verify(mongoTemplate).remove(any(Query.class), eq(EvidenceCleanupTask.class));

        // Repeated reconcile: nothing left to delete, no duplicate operations
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(EvidenceCleanupTask.class)))
                .thenReturn(null);
        EvidenceCleanupReconciler.Result repeatResult = reconciler.reconcile();
        assertThat(repeatResult.deleted()).isZero();
        assertThat(repeatResult.kept()).isZero();
        verify(storage, times(1)).delete(any()); // never called again
    }

    @Test
    @DisplayName("F6-C: recovery survives application restart when in-memory queue is lost; discovered via storage inventory")
    void recoverySurvivesApplicationRestartViaStorageInventory() {
        // 1. Simulate upload failure where enqueue into MongoDB also threw,
        // and the original store instance's in-memory queue was populated.
        store.enqueueFallback(DISPUTE_ID, EVIDENCE_ID, PUBLIC_ID, EvidenceCleanupTask.Reason.UNCERTAIN_WRITE, NOW);
        assertThat(store.getFallbackCount()).isEqualTo(1);

        // 2. The original application/store instance is completely discarded (simulating process restart)
        store = null;

        // 3. A brand-new store instance starts with no access to the original in-memory queue
        MongoTemplate freshMongo = mock(MongoTemplate.class);
        EvidenceCleanupStore freshStore = new EvidenceCleanupStore(freshMongo);
        assertThat(freshStore.getFallbackCount()).isZero(); // Fresh JVM/instance: empty queue!

        // Storage still holds the asset in Cloudinary (created well before the grace period cutoff)
        EvidenceStoragePort storage = mock(EvidenceStoragePort.class);
        when(storage.listAssets(eq("disputes/"), any(), anyInt())).thenReturn(new EvidenceStoragePort.StoragePage(
                List.of(new EvidenceStoragePort.StorageAsset(PUBLIC_ID, NOW.minus(Duration.ofHours(1)))), null));

        EvidenceCleanupReconciler freshReconciler = new EvidenceCleanupReconciler(
                freshStore, storage, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                true, Duration.ofMinutes(2), Duration.ofMinutes(5), 3, 10, Duration.ofMinutes(30), 100);

        // 4. Case A: Asset is an unreferenced orphan (original write did not commit).
        // Database availability is restored:
        when(freshMongo.findAndModify(any(), any(), any(), eq(EvidenceCleanupTask.class))).thenReturn(null);
        when(freshMongo.exists(any(Query.class), eq(com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute.class)))
                .thenReturn(false); // Unreferenced!
        when(freshMongo.exists(any(Query.class), eq(EvidenceCleanupTask.class)))
                .thenReturn(false); // No task was persisted!

        // Reconcile: discovers orphan via storage inventory and removes it
        EvidenceCleanupReconciler.Result result = freshReconciler.reconcile();

        assertThat(result.deleted()).isEqualTo(1);
        verify(storage).delete(PUBLIC_ID);

        // Repeated reconciliation is idempotent:
        when(storage.listAssets(eq("disputes/"), any(), anyInt()))
                .thenReturn(new EvidenceStoragePort.StoragePage(List.of(), null));
        EvidenceCleanupReconciler.Result repeat = freshReconciler.reconcile();
        assertThat(repeat.deleted()).isZero();
        verify(storage, times(1)).delete(anyString()); // Never deleted again
    }

    @Test
    @DisplayName("F6-C: storage inventory reconciliation preserves committed evidence and does NOT delete it")
    void storageInventoryPreservesCommittedEvidence() {
        // Fresh store instance with empty queue (after restart)
        EvidenceCleanupStore freshStore = new EvidenceCleanupStore(mongoTemplate);
        EvidenceStoragePort storage = mock(EvidenceStoragePort.class);
        when(storage.listAssets(eq("disputes/"), any(), anyInt())).thenReturn(new EvidenceStoragePort.StoragePage(
                List.of(new EvidenceStoragePort.StorageAsset(PUBLIC_ID, NOW.minus(Duration.ofHours(1)))), null));

        // Asset IS referenced in committed evidence in MongoDB
        when(mongoTemplate.exists(any(Query.class), eq(com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute.class)))
                .thenReturn(true);

        EvidenceCleanupReconciler freshReconciler = new EvidenceCleanupReconciler(
                freshStore, storage, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                true, Duration.ofMinutes(2), Duration.ofMinutes(5), 3, 10, Duration.ofMinutes(30), 100);

        EvidenceCleanupReconciler.Result result = freshReconciler.reconcile();

        assertThat(result.deleted()).isZero();
        verify(storage, never()).delete(anyString()); // Committed evidence is NEVER deleted
    }
}
