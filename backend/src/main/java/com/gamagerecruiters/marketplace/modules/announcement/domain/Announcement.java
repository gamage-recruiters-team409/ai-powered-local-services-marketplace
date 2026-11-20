package com.gamagerecruiters.marketplace.modules.announcement.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An admin announcement. {@code message} is markdown and stored raw; it is never treated as HTML (raw HTML tags are
 * rejected on input). Status changes after creation go through conditional updates in AnnouncementStore.
 */
@Document(collection = "announcements")
public class Announcement {

    @Id
    private String id;

    @Version
    private Long version;

    private String title;
    private String message;
    private AnnouncementAudience audience;
    private AnnouncementStatus status;
    /** UTC. For SCHEDULED: when the publisher publishes it. Optional for drafts. */
    private Instant scheduledAt;
    private Instant publishedAt;
    private boolean sendPush;
    private boolean pinToHeader;
    /** publishedAt + app.announcement.pin-duration, set only when pinned and published. */
    private Instant pinExpiresAt;
    private DeliveryState deliveryState;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public AnnouncementAudience getAudience() {
        return audience;
    }

    public void setAudience(AnnouncementAudience audience) {
        this.audience = audience;
    }

    public AnnouncementStatus getStatus() {
        return status;
    }

    public void setStatus(AnnouncementStatus status) {
        this.status = status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public boolean isSendPush() {
        return sendPush;
    }

    public void setSendPush(boolean sendPush) {
        this.sendPush = sendPush;
    }

    public boolean isPinToHeader() {
        return pinToHeader;
    }

    public void setPinToHeader(boolean pinToHeader) {
        this.pinToHeader = pinToHeader;
    }

    public Instant getPinExpiresAt() {
        return pinExpiresAt;
    }

    public void setPinExpiresAt(Instant pinExpiresAt) {
        this.pinExpiresAt = pinExpiresAt;
    }

    public DeliveryState getDeliveryState() {
        return deliveryState;
    }

    public void setDeliveryState(DeliveryState deliveryState) {
        this.deliveryState = deliveryState;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
