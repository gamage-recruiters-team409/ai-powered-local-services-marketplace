package com.gamagerecruiters.marketplace.modules.review.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A customer review for one completed booking (one review per booking, FR-REV-001).
 *
 * <p>Moderation (FR-REV-003): {@code moderationFlags} holds what the automated checks found (empty = clean),
 * {@code openReportCount} the number of open user reports, {@code removalReason}/{@code statusReason} the
 * moderator's reason for a removal, and {@code moderationHistory} every decision (append-only).
 */
@Document(collection = "reviews")
public class Review {

    @Id
    private String id;
    private String bookingId;
    private String listingId;
    private String providerId;
    private String customerId;
    private int rating;
    private String comment;
    private ReviewStatus status;
    private String statusReason;
    private ProviderReply providerReply;
    private boolean edited;
    private Instant editedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ModerationFlag> moderationFlags = new ArrayList<>();
    private int openReportCount;
    private RemovalReason removalReason;
    private List<ModerationEntry> moderationHistory = new ArrayList<>();

    public Review() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getListingId() {
        return listingId;
    }

    public void setListingId(String listingId) {
        this.listingId = listingId;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewStatus status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public ProviderReply getProviderReply() {
        return providerReply;
    }

    public void setProviderReply(ProviderReply providerReply) {
        this.providerReply = providerReply;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
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

    public List<ModerationFlag> getModerationFlags() {
        return moderationFlags;
    }

    public void setModerationFlags(List<ModerationFlag> moderationFlags) {
        this.moderationFlags = moderationFlags;
    }

    public int getOpenReportCount() {
        return openReportCount;
    }

    public void setOpenReportCount(int openReportCount) {
        this.openReportCount = openReportCount;
    }

    public RemovalReason getRemovalReason() {
        return removalReason;
    }

    public void setRemovalReason(RemovalReason removalReason) {
        this.removalReason = removalReason;
    }

    public List<ModerationEntry> getModerationHistory() {
        return moderationHistory;
    }

    public void setModerationHistory(List<ModerationEntry> moderationHistory) {
        this.moderationHistory = moderationHistory;
    }
}
