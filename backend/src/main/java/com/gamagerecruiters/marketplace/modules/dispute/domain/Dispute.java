package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * A dispute raised by a customer or provider about one booking.
 *
 * <p>Status changes go through {@link #changeStatus}, which always appends a {@link StatusHistoryEntry}.</p>
 *
 * <p>{@code activeBookingKey} holds the bookingId while the dispute is active and is removed when it
 * stops being active. A unique partial index on it (see DisputeIndexInitializer) guarantees at most one
 * active dispute per booking, even under concurrent requests.</p>
 *
 * <p>{@code version} gives optimistic locking, so two concurrent updates cannot both succeed.</p>
 */
@Document(collection = "disputes")
public class Dispute {

    @Id
    private String id;

    @Version
    private Long version;

    private String bookingId;
    private String customerId;
    private String providerId;
    private String raisedBy;
    private Role raisedByRole;
    private DisputeCategory category;
    private String description;
    private List<String> evidence = new ArrayList<>();
    private DisputeStatus status;
    private String activeBookingKey;
    private PartyResponse response;
    private Instant responseDueAt;
    private Instant createdAt;
    private Instant updatedAt;
    private List<StatusHistoryEntry> statusHistory = new ArrayList<>();

    /**
     * Moves the dispute to {@code toStatus} and records the change in the status history.
     * Callers validate whether the transition is allowed before calling this.
     */
    public void changeStatus(DisputeStatus toStatus, String actorId, Role actorRole, String reason, Instant at) {
        statusHistory.add(new StatusHistoryEntry(status, toStatus, actorId, actorRole, reason, at));
        status = toStatus;
        activeBookingKey = toStatus.isActive() ? bookingId : null;
        updatedAt = at;
    }

    /** True if the user is the customer or the provider of the disputed booking. */
    public boolean isParty(String userId) {
        return userId != null && (userId.equals(customerId) || userId.equals(providerId));
    }

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

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getRaisedBy() {
        return raisedBy;
    }

    public void setRaisedBy(String raisedBy) {
        this.raisedBy = raisedBy;
    }

    public Role getRaisedByRole() {
        return raisedByRole;
    }

    public void setRaisedByRole(Role raisedByRole) {
        this.raisedByRole = raisedByRole;
    }

    public DisputeCategory getCategory() {
        return category;
    }

    public void setCategory(DisputeCategory category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public void setStatus(DisputeStatus status) {
        this.status = status;
    }

    public String getActiveBookingKey() {
        return activeBookingKey;
    }

    public void setActiveBookingKey(String activeBookingKey) {
        this.activeBookingKey = activeBookingKey;
    }

    public PartyResponse getResponse() {
        return response;
    }

    public void setResponse(PartyResponse response) {
        this.response = response;
    }

    public Instant getResponseDueAt() {
        return responseDueAt;
    }

    public void setResponseDueAt(Instant responseDueAt) {
        this.responseDueAt = responseDueAt;
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

    /** Read-only view; add entries only through {@link #changeStatus}. */
    public List<StatusHistoryEntry> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }

    /** Used by the MongoDB mapper when reading the document. */
    public void setStatusHistory(List<StatusHistoryEntry> statusHistory) {
        this.statusHistory = statusHistory == null ? new ArrayList<>() : new ArrayList<>(statusHistory);
    }
}
