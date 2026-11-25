package com.gamagerecruiters.marketplace.modules.customer.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupReason;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupStatus;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupTask;
import com.gamagerecruiters.marketplace.modules.customer.repository.ProfileImageCleanupRepository;

/**
 * Service orchestrating reliable, recoverable deletion of customer profile images in Cloudinary.
 * Guarantees that active user images are never deleted through strict DB reference verification.
 */
@Service
public class ProfileImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageCleanupService.class);

    private final ProfileImageCleanupRepository repository;
    private final CustomerProfileImageStorage storage;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final Duration leaseDuration;

    public ProfileImageCleanupService(
            ProfileImageCleanupRepository repository,
            CustomerProfileImageStorage storage,
            MongoTemplate mongoTemplate,
            Optional<Clock> clock,
            @Value("${app.customer.profile-image-cleanup.lease:PT2M}") Duration leaseDuration
    ) {
        this.repository = repository;
        this.storage = storage;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock != null && clock.isPresent() ? clock.get() : Clock.systemUTC();
        this.leaseDuration = leaseDuration != null ? leaseDuration : Duration.ofMinutes(2);
    }

    /**
     * Schedules a cleanup task atomically using upsert + returnNew.
     * Re-opens DONE tasks to PENDING. Catches DuplicateKeyException to safely re-read.
     * Never returns null; never throws for duplicate scheduling.
     *
     * @param publicId    remote Cloudinary public ID
     * @param ownerUserId customer ID owning the photo
     * @param reason      trigger reason for the cleanup
     * @return the saved, existing, or reopened cleanup task
     */
    public ProfileImageCleanupTask schedule(String publicId, String ownerUserId, ProfileImageCleanupReason reason) {
        if (publicId == null || publicId.isBlank()) {
            return null;
        }

        String cleanPublicId = publicId.trim();
        Instant now = now();

        Query query = Query.query(Criteria.where("publicId").is(cleanPublicId));
        Update update = new Update()
                .setOnInsert("publicId", cleanPublicId)
                .setOnInsert("ownerUserId", ownerUserId)
                .setOnInsert("reason", reason)
                .setOnInsert("status", ProfileImageCleanupStatus.PENDING)
                .setOnInsert("attempts", 0)
                .setOnInsert("createdAt", now)
                .setOnInsert("nextAttemptAt", now);
        FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);

        ProfileImageCleanupTask task;
        try {
            task = mongoTemplate.findAndModify(query, update, options, ProfileImageCleanupTask.class);
        } catch (DuplicateKeyException ex) {
            log.debug("Concurrent upsert hit duplicate key for publicId={}, re-reading task", cleanPublicId);
            task = repository.findByPublicId(cleanPublicId).orElseGet(() ->
                    mongoTemplate.findOne(query, ProfileImageCleanupTask.class));
        } catch (Exception ex) {
            log.error("Failed to schedule cleanup task: ownerUserId={}, publicId={}, error={}",
                    ownerUserId, cleanPublicId, ex.getClass().getSimpleName());
            return new ProfileImageCleanupTask(cleanPublicId, ownerUserId, reason);
        }

        // F4 / F3: If the returned task is DONE, re-open it to PENDING
        if (task != null && task.getStatus() == ProfileImageCleanupStatus.DONE) {
            Query reopenQuery = Query.query(Criteria.where("publicId").is(cleanPublicId)
                    .and("status").is(ProfileImageCleanupStatus.DONE));
            Update reopenUpdate = new Update()
                    .set("status", ProfileImageCleanupStatus.PENDING)
                    .set("nextAttemptAt", now)
                    .unset("leaseUntil");
            try {
                ProfileImageCleanupTask reopened = mongoTemplate.findAndModify(
                        reopenQuery, reopenUpdate, FindAndModifyOptions.options().returnNew(true), ProfileImageCleanupTask.class);
                if (reopened != null) {
                    task = reopened;
                }
            } catch (Exception ex) {
                log.warn("Failed to reopen DONE cleanup task: publicId={}, error={}",
                        cleanPublicId, ex.getClass().getSimpleName());
            }
        }

        return task != null ? task : new ProfileImageCleanupTask(cleanPublicId, ownerUserId, reason);
    }

    /**
     * Attempts remote storage deletion for a cleanup task with atomic lease claiming.
     * Enforces the core invariant: verifies that no user currently references the image
     * before deleting.
     *
     * @param task cleanup task to process
     * @return true if deletion succeeded, false otherwise
     */
    public boolean attempt(ProfileImageCleanupTask task) {
        if (task == null || task.getPublicId() == null || task.getPublicId().isBlank()) {
            return false;
        }

        String cleanPublicId = task.getPublicId().trim();
        Instant now = now();

        Query claimQuery = Query.query(
                Criteria.where("publicId").is(cleanPublicId)
                        .and("status").is(ProfileImageCleanupStatus.PENDING)
                        .andOperator(new Criteria().orOperator(
                                Criteria.where("leaseUntil").exists(false),
                                Criteria.where("leaseUntil").is(null),
                                Criteria.where("leaseUntil").lte(now)
                        ))
        );
        Update claimUpdate = new Update()
                .set("leaseUntil", now.plus(leaseDuration))
                .inc("attempts", 1);

        ProfileImageCleanupTask claimed;
        try {
            claimed = mongoTemplate.findAndModify(
                    claimQuery, claimUpdate, FindAndModifyOptions.options().returnNew(true), ProfileImageCleanupTask.class);
        } catch (Exception ex) {
            log.warn("Failed to claim cleanup task: publicId={}, error={}",
                    cleanPublicId, ex.getClass().getSimpleName());
            return false;
        }

        if (claimed == null) {
            return false;
        }

        return processClaimedTask(claimed, now);
    }

    /**
     * Retries pending cleanup tasks up to batchSize with atomic lease claiming.
     * Each claimed task runs through the exact same reference-safety check.
     *
     * @param batchSize maximum number of pending tasks to process
     * @return count of processed cleanup tasks in this batch
     */
    public int retryPending(int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 100));
        int processed = 0;
        Instant now = now();

        for (int i = 0; i < limit; i++) {
            Query claimQuery = Query.query(
                    Criteria.where("status").is(ProfileImageCleanupStatus.PENDING)
                            .and("nextAttemptAt").lte(now)
                            .andOperator(new Criteria().orOperator(
                                    Criteria.where("leaseUntil").exists(false),
                                    Criteria.where("leaseUntil").is(null),
                                    Criteria.where("leaseUntil").lte(now)
                            ))
            ).with(Sort.by(Sort.Direction.ASC, "createdAt"));

            Update claimUpdate = new Update()
                    .set("leaseUntil", now.plus(leaseDuration))
                    .inc("attempts", 1);

            ProfileImageCleanupTask claimed;
            try {
                claimed = mongoTemplate.findAndModify(
                        claimQuery,
                        claimUpdate,
                        FindAndModifyOptions.options().returnNew(true),
                        ProfileImageCleanupTask.class
                );
            } catch (Exception ex) {
                log.warn("Error claiming task in retryPending: error={}", ex.getClass().getSimpleName());
                break;
            }

            if (claimed == null) {
                break;
            }

            processClaimedTask(claimed, now);
            processed++;
        }

        return processed;
    }

    private boolean processClaimedTask(ProfileImageCleanupTask task, Instant now) {
        String publicId = task.getPublicId();
        String ownerUserId = task.getOwnerUserId();

        // 1. Reference safety check: is publicId currently referenced by ANY user?
        boolean isReferenced;
        try {
            Query query = Query.query(Criteria.where("profileImagePublicId").is(publicId));
            isReferenced = mongoTemplate.exists(query, User.class);
        } catch (Exception ex) {
            log.warn("Reference safety check failed for publicId={}, error={}",
                    publicId, ex.getClass().getSimpleName());
            recordFailure(task, "ReferenceCheckFailed", now);
            return false;
        }

        // 2. If referenced: mark DONE (lastErrorClass "ReferencedByUser"), do NOT delete
        if (isReferenced) {
            log.info("Refusing to delete publicId currently referenced by user: publicId={}, ownerUserId={}",
                    publicId, ownerUserId);
            markDone(task, "ReferencedByUser", now);
            return false;
        }

        // 3. Unreferenced: attempt storage.delete
        try {
            boolean deleted = storage.delete(publicId);
            if (deleted) {
                markDone(task, null, now);
                return true;
            } else {
                recordFailure(task, "StorageDeleteFailed", now);
                return false;
            }
        } catch (Exception ex) {
            log.warn("Storage deletion failed for publicId={}, error={}",
                    publicId, ex.getClass().getSimpleName());
            recordFailure(task, ex.getClass().getSimpleName(), now);
            return false;
        }
    }

    private void markDone(ProfileImageCleanupTask task, String errorClass, Instant now) {
        Query query = Query.query(Criteria.where("publicId").is(task.getPublicId()));
        Update update = new Update()
                .set("status", ProfileImageCleanupStatus.DONE)
                .set("lastAttemptAt", now)
                .set("lastErrorClass", errorClass)
                .unset("leaseUntil");
        try {
            mongoTemplate.updateFirst(query, update, ProfileImageCleanupTask.class);
        } catch (Exception ex) {
            log.warn("Failed to mark cleanup task as DONE: publicId={}, error={}",
                    task.getPublicId(), ex.getClass().getSimpleName());
        }

        task.setStatus(ProfileImageCleanupStatus.DONE);
        task.setLastAttemptAt(now);
        task.setLastErrorClass(errorClass);
        task.setLeaseUntil(null);
    }

    private void recordFailure(ProfileImageCleanupTask task, String errorClass, Instant now) {
        Instant nextAttempt = calculateNextAttemptAt(now, task.getAttempts());

        Query query = Query.query(Criteria.where("publicId").is(task.getPublicId()));
        Update update = new Update()
                .set("lastAttemptAt", now)
                .set("lastErrorClass", errorClass)
                .set("nextAttemptAt", nextAttempt)
                .unset("leaseUntil");
        try {
            mongoTemplate.updateFirst(query, update, ProfileImageCleanupTask.class);
        } catch (Exception ex) {
            log.warn("Failed to record failure for cleanup task: publicId={}, error={}",
                    task.getPublicId(), ex.getClass().getSimpleName());
        }

        task.setLastAttemptAt(now);
        task.setLastErrorClass(errorClass);
        task.setNextAttemptAt(nextAttempt);
        task.setLeaseUntil(null);
    }

    private Instant calculateNextAttemptAt(Instant now, int attempts) {
        if (attempts <= 0) {
            return now.plus(Duration.ofMinutes(1));
        }
        if (attempts >= 9) { // 2^9 = 512 min > 360 min (6 hours)
            return now.plus(Duration.ofHours(6));
        }
        long minutes = Math.min(1L << attempts, 360L);
        return now.plus(Duration.ofMinutes(minutes));
    }

    private Instant now() {
        return clock.instant();
    }
}
