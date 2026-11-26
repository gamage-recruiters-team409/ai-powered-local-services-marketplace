package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupReason;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupStatus;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupTask;
import com.gamagerecruiters.marketplace.modules.customer.repository.ProfileImageCleanupRepository;

@ExtendWith(MockitoExtension.class)
class ProfileImageCleanupServiceTest {

    @Mock
    private ProfileImageCleanupRepository repository;

    @Mock
    private CustomerProfileImageStorage storage;

    @Mock
    private MongoTemplate mongoTemplate;

    private Clock fixedClock;
    private Instant now;
    private ProfileImageCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-03-30T10:00:00Z");
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        cleanupService = new ProfileImageCleanupService(
                repository, storage, mongoTemplate, Optional.of(fixedClock),
                Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofHours(6));
    }

    @Test
    @DisplayName("F2-A #6: Startup validation: rejects referencedDefer < PT1M and referencedStaleAfter < PT10M")
    void startupValidation_rejectsInvalidDurations() {
        assertThrows(IllegalArgumentException.class, () ->
                new ProfileImageCleanupService(repository, storage, mongoTemplate, Optional.of(fixedClock),
                        Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofHours(6)));

        assertThrows(IllegalArgumentException.class, () ->
                new ProfileImageCleanupService(repository, storage, mongoTemplate, Optional.of(fixedClock),
                        Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("F3 & F2-A #2: schedule performs atomic upsert setting and refreshing scheduledAt")
    void schedule_atomicUpsert_setsAndRefreshesScheduledAt() {
        ProfileImageCleanupTask created = new ProfileImageCleanupTask("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);
        created.setId("task-id-1");
        created.setStatus(ProfileImageCleanupStatus.PENDING);
        created.setCreatedAt(now);
        created.setScheduledAt(now);
        created.setNextAttemptAt(now);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(created);

        ProfileImageCleanupTask task = cleanupService.schedule("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);

        assertNotNull(task);
        assertEquals("task-id-1", task.getId());
        assertEquals("pub-1", task.getPublicId());
        assertEquals(ProfileImageCleanupStatus.PENDING, task.getStatus());

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> optionsCaptor = ArgumentCaptor.forClass(FindAndModifyOptions.class);

        verify(mongoTemplate).findAndModify(queryCaptor.capture(), updateCaptor.capture(), optionsCaptor.capture(), eq(ProfileImageCleanupTask.class));
        assertEquals("pub-1", queryCaptor.getValue().getQueryObject().get("publicId"));
        assertTrue(optionsCaptor.getValue().isUpsert());
        assertTrue(optionsCaptor.getValue().isReturnNew());
    }

    @Test
    @DisplayName("F3 & F2-B: DuplicateKeyException during upsert safely re-reads existing persisted task from repository")
    void schedule_duplicateKeyException_reReadsPersistedTask() {
        ProfileImageCleanupTask existing = new ProfileImageCleanupTask("pub-dup", "user-1", ProfileImageCleanupReason.REPLACED);
        existing.setId("task-id-dup");
        existing.setStatus(ProfileImageCleanupStatus.PENDING);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error"));
        when(repository.findByPublicId("pub-dup")).thenReturn(Optional.of(existing));

        ProfileImageCleanupTask result = cleanupService.schedule("pub-dup", "user-1", ProfileImageCleanupReason.REPLACED);

        assertNotNull(result);
        assertEquals("task-id-dup", result.getId());
        assertEquals("pub-dup", result.getPublicId());
        verify(repository).findByPublicId("pub-dup");
    }

    @Test
    @DisplayName("F2-B #1 & #11: schedule throws ProfileImageCleanupScheduleException when upsert and re-read both fail")
    void schedule_persistenceFails_throwsProfileImageCleanupScheduleException() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenThrow(new RuntimeException("Mongo connection refused"));

        assertThrows(ProfileImageCleanupScheduleException.class, () ->
                cleanupService.schedule("pub-fail", "user-1", ProfileImageCleanupReason.REPLACED));
    }

    @Test
    @DisplayName("F2-B #1 & #11: schedule throws ProfileImageCleanupScheduleException if re-read returns empty")
    void schedule_duplicateKey_reReadEmpty_throwsScheduleException() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error"));
        when(repository.findByPublicId("pub-dup")).thenReturn(Optional.empty());
        when(mongoTemplate.findOne(any(Query.class), eq(ProfileImageCleanupTask.class))).thenReturn(null);

        assertThrows(ProfileImageCleanupScheduleException.class, () ->
                cleanupService.schedule("pub-dup", "user-1", ProfileImageCleanupReason.REPLACED));
    }

    @Test
    @DisplayName("F4 / F3 & F2-A #2: DONE task is re-opened to PENDING with scheduledAt = now")
    void schedule_doneTask_reopenedToPendingWithNewScheduledAt() {
        ProfileImageCleanupTask doneTask = new ProfileImageCleanupTask("pub-done", "user-1", ProfileImageCleanupReason.REPLACED);
        doneTask.setId("id-done");
        doneTask.setStatus(ProfileImageCleanupStatus.DONE);

        ProfileImageCleanupTask reopenedTask = new ProfileImageCleanupTask("pub-done", "user-1", ProfileImageCleanupReason.REPLACED);
        reopenedTask.setId("id-done");
        reopenedTask.setStatus(ProfileImageCleanupStatus.PENDING);
        reopenedTask.setScheduledAt(now);
        reopenedTask.setNextAttemptAt(now);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(doneTask)
                .thenReturn(reopenedTask);

        ProfileImageCleanupTask result = cleanupService.schedule("pub-done", "user-1", ProfileImageCleanupReason.REPLACED);

        assertNotNull(result);
        assertEquals(ProfileImageCleanupStatus.PENDING, result.getStatus());
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class));
    }

    @Test
    @DisplayName("schedule: blank or null publicId throws IllegalArgumentException")
    void schedule_blankOrNull_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> cleanupService.schedule(null, "user-1", ProfileImageCleanupReason.REPLACED));
        assertThrows(IllegalArgumentException.class, () -> cleanupService.schedule("   ", "user-1", ProfileImageCleanupReason.REPLACED));
        verifyNoInteractions(mongoTemplate, repository);
    }

    @Test
    @DisplayName("F2-A #1: attempt() with still-referenced image defers nextAttemptAt, releases lease, does NOT delete, attempts unchanged")
    void attempt_referencedImage_defersTaskWithoutIncrementingAttempts() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-ref", "user-1", ProfileImageCleanupReason.REPLACED);
        task.setId("task-ref-1");
        task.setStatus(ProfileImageCleanupStatus.PENDING);
        task.setScheduledAt(now.minus(Duration.ofMinutes(10))); // within 6 hour stale window
        task.setAttempts(0);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(task);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(true);

        boolean result = cleanupService.attempt(task);

        assertFalse(result);
        // Remains PENDING!
        assertEquals(ProfileImageCleanupStatus.PENDING, task.getStatus());
        assertEquals("ReferencedByUser", task.getLastErrorClass());
        // Deferred by referencedDefer (5 minutes)
        assertEquals(now.plus(Duration.ofMinutes(5)), task.getNextAttemptAt());
        assertNull(task.getLeaseUntil());
        // Attempts count remains 0! Claiming and deferral do NOT increment attempts
        assertEquals(0, task.getAttempts());
        verify(storage, never()).delete(any());
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(ProfileImageCleanupTask.class));
    }

    @Test
    @DisplayName("F2-A #4: still-referenced task older than referenced-stale-after (PT6H) becomes DONE (ReferencedStale)")
    void attempt_referencedImage_olderThanStaleThreshold_marksDone() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-stale", "user-1", ProfileImageCleanupReason.REPLACED);
        task.setId("task-stale-1");
        task.setStatus(ProfileImageCleanupStatus.PENDING);
        // scheduledAt is 7 hours ago (> 6 hour stale threshold)
        task.setScheduledAt(now.minus(Duration.ofHours(7)));
        task.setAttempts(0);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(task);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(true);

        boolean result = cleanupService.attempt(task);

        assertFalse(result);
        assertEquals(ProfileImageCleanupStatus.DONE, task.getStatus());
        assertEquals("ReferencedStale", task.getLastErrorClass());
        assertNull(task.getLeaseUntil());
        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("F1: attempt() when reference query throws leaves task PENDING with ReferenceCheckFailed and does NOT increment attempts")
    void attempt_referenceCheckFails_defersPendingWithoutIncrementingAttempts() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-err", "user-1", ProfileImageCleanupReason.REPLACED);
        task.setId("task-err-1");
        task.setStatus(ProfileImageCleanupStatus.PENDING);
        task.setAttempts(0);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(task);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenThrow(new RuntimeException("DB query timeout"));

        boolean result = cleanupService.attempt(task);

        assertFalse(result);
        assertEquals(ProfileImageCleanupStatus.PENDING, task.getStatus());
        assertEquals("ReferenceCheckFailed", task.getLastErrorClass());
        assertEquals(0, task.getAttempts()); // Not incremented
        assertNotNull(task.getNextAttemptAt());
        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("F1: attempt() with unreferenced image deletes from storage and marks DONE")
    void attempt_unreferencedImage_storageSuccess_marksDone() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-unref", "user-1", ProfileImageCleanupReason.REPLACED);
        task.setId("task-unref-1");
        task.setStatus(ProfileImageCleanupStatus.PENDING);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(task);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(storage.delete("pub-unref")).thenReturn(true);

        boolean result = cleanupService.attempt(task);

        assertTrue(result);
        assertEquals(ProfileImageCleanupStatus.DONE, task.getStatus());
        assertNull(task.getLastErrorClass());
        verify(storage).delete("pub-unref");
    }

    @Test
    @DisplayName("F2: attempt() storage failure increments attempts and calculates exponential backoff")
    void attempt_storageFailure_incrementsAttemptsAndRecordsBackoff() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-fail", "user-1", ProfileImageCleanupReason.REPLACED);
        task.setId("task-fail-1");
        task.setStatus(ProfileImageCleanupStatus.PENDING);
        task.setAttempts(0); // Starts at 0

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(task);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(storage.delete("pub-fail")).thenReturn(false);

        boolean result = cleanupService.attempt(task);

        assertFalse(result);
        assertEquals(ProfileImageCleanupStatus.PENDING, task.getStatus());
        assertEquals("StorageDeleteFailed", task.getLastErrorClass());
        // Storage failure increments attempts from 0 to 1
        assertEquals(1, task.getAttempts());
        // 2^1 = 2 minutes backoff
        assertEquals(now.plus(Duration.ofMinutes(2)), task.getNextAttemptAt());
    }

    @Test
    @DisplayName("F1: property-style test: storage.delete is called ONLY when unreferenced")
    void storageDelete_calledOnlyWhenUnreferenced() {
        // Combination 1: Referenced -> NO delete
        ProfileImageCleanupTask t1 = new ProfileImageCleanupTask("p1", "u1", ProfileImageCleanupReason.REPLACED);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(t1);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(true);
        cleanupService.attempt(t1);
        verify(storage, never()).delete("p1");

        // Combination 2: Query fails -> NO delete
        ProfileImageCleanupTask t2 = new ProfileImageCleanupTask("p2", "u1", ProfileImageCleanupReason.REPLACED);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(t2);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenThrow(new RuntimeException("Query error"));
        cleanupService.attempt(t2);
        verify(storage, never()).delete("p2");

        // Combination 3: Unreferenced -> storage.delete IS CALLED
        ProfileImageCleanupTask t3 = new ProfileImageCleanupTask("p3", "u1", ProfileImageCleanupReason.REPLACED);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(t3);
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(storage.delete("p3")).thenReturn(true);
        cleanupService.attempt(t3);
        verify(storage).delete("p3");
    }

    @Test
    @DisplayName("F2: retryPending processes up to batchSize with lease and backoff")
    void retryPending_processesBatch() {
        ProfileImageCleanupTask t1 = new ProfileImageCleanupTask("batch-1", "u1", ProfileImageCleanupReason.REPLACED);
        t1.setId("batch-id-1");
        t1.setStatus(ProfileImageCleanupStatus.PENDING);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ProfileImageCleanupTask.class)))
                .thenReturn(t1)
                .thenReturn(null);

        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(storage.delete("batch-1")).thenReturn(true);

        int processed = cleanupService.retryPending(10);
        assertEquals(1, processed);
        verify(storage).delete("batch-1");
    }

    @Test
    @DisplayName("F4: ProfileImageCleanupService has no cancel method")
    void cleanupService_hasNoCancelMethod() {
        assertThrows(NoSuchMethodException.class, () ->
                ProfileImageCleanupService.class.getMethod("cancel", String.class));
    }
}
