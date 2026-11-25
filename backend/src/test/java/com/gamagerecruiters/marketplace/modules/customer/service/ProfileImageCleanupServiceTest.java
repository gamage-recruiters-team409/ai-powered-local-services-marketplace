package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

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

    private ProfileImageCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new ProfileImageCleanupService(repository, storage, mongoTemplate);
    }

    @Test
    @DisplayName("schedule: creates and persists PENDING task when none exists")
    void schedule_newPublicId_createsPendingTask() {
        when(repository.findByPublicIdAndStatus("public-123", ProfileImageCleanupStatus.PENDING))
                .thenReturn(Optional.empty());
        when(repository.save(any(ProfileImageCleanupTask.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProfileImageCleanupTask task = cleanupService.schedule(
                "public-123", "user-1", ProfileImageCleanupReason.REPLACED);

        assertNotNull(task);
        assertEquals("public-123", task.getPublicId());
        assertEquals("user-1", task.getOwnerUserId());
        assertEquals(ProfileImageCleanupReason.REPLACED, task.getReason());
        assertEquals(ProfileImageCleanupStatus.PENDING, task.getStatus());
        assertEquals(0, task.getAttempts());
        assertNotNull(task.getCreatedAt());

        verify(repository).save(any(ProfileImageCleanupTask.class));
    }

    @Test
    @DisplayName("schedule: duplicate schedule for same publicId does not create a second task")
    void schedule_existingPending_returnsExistingWithoutSaving() {
        ProfileImageCleanupTask existing = new ProfileImageCleanupTask("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);
        when(repository.findByPublicIdAndStatus("pub-1", ProfileImageCleanupStatus.PENDING))
                .thenReturn(Optional.of(existing));

        ProfileImageCleanupTask task = cleanupService.schedule("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);

        assertEquals(existing, task);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("schedule: blank or null publicId returns null without repository query")
    void schedule_blankOrNull_returnsNull() {
        assertNull(cleanupService.schedule(null, "user-1", ProfileImageCleanupReason.REPLACED));
        assertNull(cleanupService.schedule("   ", "user-1", ProfileImageCleanupReason.REPLACED));
        verify(repository, never()).findByPublicIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("attempt: storage delete success marks status DONE")
    void attempt_storageSuccess_marksDone() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);
        when(storage.delete("pub-1")).thenReturn(true);

        boolean result = cleanupService.attempt(task);

        assertTrue(result);
        assertEquals(ProfileImageCleanupStatus.DONE, task.getStatus());
        assertNotNull(task.getLastAttemptAt());
        assertNull(task.getLastErrorClass());
        verify(repository).save(task);
    }

    @Test
    @DisplayName("attempt: storage delete failure increments attempts and sets lastErrorClass without leaking sensitive text")
    void attempt_storageFailure_incrementsAttemptsAndSetsErrorClass() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);
        when(storage.delete("pub-1")).thenReturn(false);

        boolean result = cleanupService.attempt(task);

        assertFalse(result);
        assertEquals(ProfileImageCleanupStatus.PENDING, task.getStatus());
        assertEquals(1, task.getAttempts());
        assertNotNull(task.getLastAttemptAt());
        assertEquals("StorageDeleteFailed", task.getLastErrorClass());
        verify(repository).save(task);
    }

    @Test
    @DisplayName("attempt: exception during delete increments attempts and records simple class name")
    void attempt_storageException_recordsSimpleClassName() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);
        when(storage.delete("pub-1")).thenThrow(new IllegalStateException("secret-token-123"));

        boolean result = cleanupService.attempt(task);

        assertFalse(result);
        assertEquals(ProfileImageCleanupStatus.PENDING, task.getStatus());
        assertEquals(1, task.getAttempts());
        assertEquals("IllegalStateException", task.getLastErrorClass());
        verify(repository).save(task);
    }

    @Test
    @DisplayName("cancel: deletes pending cleanup task if present")
    void cancel_pendingTaskExists_deletesTask() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);
        when(repository.findByPublicIdAndStatus("pub-1", ProfileImageCleanupStatus.PENDING))
                .thenReturn(Optional.of(task));

        cleanupService.cancel("pub-1");

        verify(repository).delete(task);
    }

    @Test
    @DisplayName("retryPending: processes PENDING tasks that are not referenced by any user")
    void retryPending_unreferencedTasks_processesAndDeletes() {
        ProfileImageCleanupTask task1 = new ProfileImageCleanupTask("pub-1", "user-1", ProfileImageCleanupReason.REPLACED);
        ProfileImageCleanupTask task2 = new ProfileImageCleanupTask("pub-2", "user-2", ProfileImageCleanupReason.REMOVED);

        when(repository.findByStatusOrderByCreatedAtAsc(eq(ProfileImageCleanupStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(task1, task2));
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(false);
        when(storage.delete("pub-1")).thenReturn(true);
        when(storage.delete("pub-2")).thenReturn(true);

        int succeeded = cleanupService.retryPending(10);

        assertEquals(2, succeeded);
        assertEquals(ProfileImageCleanupStatus.DONE, task1.getStatus());
        assertEquals(ProfileImageCleanupStatus.DONE, task2.getStatus());
        verify(storage).delete("pub-1");
        verify(storage).delete("pub-2");
    }

    @Test
    @DisplayName("retryPending CRITICAL SAFETY: skips and marks DONE any task whose publicId is currently referenced by any User")
    void retryPending_currentlyReferencedByUser_refusesDeleteAndMarksDone() {
        ProfileImageCleanupTask task = new ProfileImageCleanupTask("active-pub-id", "user-1", ProfileImageCleanupReason.REPLACED);

        when(repository.findByStatusOrderByCreatedAtAsc(eq(ProfileImageCleanupStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(task));
        // User exists with profileImagePublicId == "active-pub-id"
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(true);

        int succeeded = cleanupService.retryPending(10);

        assertEquals(0, succeeded);
        // Storage delete must NEVER be called for an active photo!
        verify(storage, never()).delete(any());
        assertEquals(ProfileImageCleanupStatus.DONE, task.getStatus());
        assertEquals("ReferencedByUser", task.getLastErrorClass());
        verify(repository).save(task);
    }
}
