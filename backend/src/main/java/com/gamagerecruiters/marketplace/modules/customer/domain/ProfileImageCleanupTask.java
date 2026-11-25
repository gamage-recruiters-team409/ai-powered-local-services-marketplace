package com.gamagerecruiters.marketplace.modules.customer.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Durable record for asynchronous or retryable cleanup of remote Cloudinary customer profile images.
 */
@Document(collection = "profile_image_cleanup")
@CompoundIndex(name = "status_1_createdAt_1", def = "{'status': 1, 'createdAt': 1}")
public class ProfileImageCleanupTask {

    @Id
    private String id;

    @Indexed(unique = true)
    private String publicId;

    private String ownerUserId;

    private ProfileImageCleanupReason reason;

    private ProfileImageCleanupStatus status = ProfileImageCleanupStatus.PENDING;

    private int attempts = 0;

    private Instant createdAt = Instant.now();

    private Instant lastAttemptAt;

    private String lastErrorClass;

    public ProfileImageCleanupTask() {
    }

    public ProfileImageCleanupTask(
            String publicId,
            String ownerUserId,
            ProfileImageCleanupReason reason
    ) {
        this.publicId = publicId;
        this.ownerUserId = ownerUserId;
        this.reason = reason;
        this.status = ProfileImageCleanupStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public ProfileImageCleanupReason getReason() {
        return reason;
    }

    public void setReason(ProfileImageCleanupReason reason) {
        this.reason = reason;
    }

    public ProfileImageCleanupStatus getStatus() {
        return status;
    }

    public void setStatus(ProfileImageCleanupStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public String getLastErrorClass() {
        return lastErrorClass;
    }

    public void setLastErrorClass(String lastErrorClass) {
        this.lastErrorClass = lastErrorClass;
    }
}
