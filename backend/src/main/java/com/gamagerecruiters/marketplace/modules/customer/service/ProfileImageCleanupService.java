package com.gamagerecruiters.marketplace.modules.customer.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupReason;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupStatus;
import com.gamagerecruiters.marketplace.modules.customer.domain.ProfileImageCleanupTask;
import com.gamagerecruiters.marketplace.modules.customer.repository.ProfileImageCleanupRepository;

/**
 * Service orchestrating reliable, recoverable deletion of customer profile images in Cloudinary.
 */
@Service
public class ProfileImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageCleanupService.class);

    private final ProfileImageCleanupRepository repository;
    private final CustomerProfileImageStorage storage;
    private final MongoTemplate mongoTemplate;

    public ProfileImageCleanupService(
            ProfileImageCleanupRepository repository,
            CustomerProfileImageStorage storage,
            MongoTemplate mongoTemplate
    ) {
        this.repository = repository;
        this.storage = storage;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Schedules a cleanup task in PENDING status before or right after an operation.
     * Prevents duplicates if a PENDING task already exists for the publicId.
     *
     * @param publicId    remote Cloudinary public ID
     * @param ownerUserId customer ID owning the photo
     * @param reason      trigger reason for the cleanup
     * @return the saved or existing cleanup task, or null if publicId is blank
     */
    public ProfileImageCleanupTask schedule(String publicId, String ownerUserId, ProfileImageCleanupReason reason) {
        if (publicId == null || publicId.isBlank()) {
            return null;
        }

        String cleanPublicId = publicId.trim();
        Optional<ProfileImageCleanupTask> existing =
                repository.findByPublicIdAndStatus(cleanPublicId, ProfileImageCleanupStatus.PENDING);
        if (existing.isPresent()) {
            return existing.get();
        }

        ProfileImageCleanupTask task = new ProfileImageCleanupTask(cleanPublicId, ownerUserId, reason);
        return repository.save(task);
    }

    /**
     * Attempts remote storage deletion for a cleanup task.
     * Marks task as DONE on success or "not found", increments attempt count on failure.
     *
     * @param task cleanup task to process
     * @return true if deletion succeeded, false otherwise
     */
    public boolean attempt(ProfileImageCleanupTask task) {
        if (task == null || task.getPublicId() == null || task.getPublicId().isBlank()) {
            return false;
        }

        try {
            boolean success = storage.delete(task.getPublicId());
            task.setLastAttemptAt(Instant.now());
            if (success) {
                task.setStatus(ProfileImageCleanupStatus.DONE);
                task.setLastErrorClass(null);
                repository.save(task);
                return true;
            } else {
                task.setAttempts(task.getAttempts() + 1);
                task.setLastErrorClass("StorageDeleteFailed");
                repository.save(task);
                return false;
            }
        } catch (Exception ex) {
            log.warn("Error attempting profile image cleanup: userId={}, error={}",
                    task.getOwnerUserId(), ex.getClass().getSimpleName());
            task.setLastAttemptAt(Instant.now());
            task.setAttempts(task.getAttempts() + 1);
            task.setLastErrorClass(ex.getClass().getSimpleName());
            repository.save(task);
            return false;
        }
    }

    /**
     * Cancels any pending cleanup task for the given publicId (e.g. when an update race means
     * the image was not replaced).
     *
     * @param publicId remote Cloudinary public ID
     */
    public void cancel(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }

        repository.findByPublicIdAndStatus(publicId.trim(), ProfileImageCleanupStatus.PENDING)
                .ifPresent(repository::delete);
    }

    /**
     * Retries pending cleanup tasks up to batchSize.
     * Before deleting, verifies that the publicId is not currently referenced by any user.
     *
     * @param batchSize maximum number of pending tasks to process
     * @return count of successfully completed cleanup tasks
     */
    public int retryPending(int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 100));
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "createdAt"));
        List<ProfileImageCleanupTask> pendingTasks =
                repository.findByStatusOrderByCreatedAtAsc(ProfileImageCleanupStatus.PENDING, pageable);

        int succeeded = 0;
        for (ProfileImageCleanupTask task : pendingTasks) {
            // Safety check: ensure not referenced by any user
            Query query = Query.query(Criteria.where("profileImagePublicId").is(task.getPublicId()));
            boolean isReferenced = mongoTemplate.exists(query, User.class);
            if (isReferenced) {
                log.warn("Refusing to delete publicId currently referenced by user: publicId={}, ownerUserId={}",
                        task.getPublicId(), task.getOwnerUserId());
                task.setStatus(ProfileImageCleanupStatus.DONE);
                task.setLastAttemptAt(Instant.now());
                task.setLastErrorClass("ReferencedByUser");
                repository.save(task);
                continue;
            }

            if (attempt(task)) {
                succeeded++;
            }
        }
        return succeeded;
    }
}
