package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * A dispute raised by a customer or provider about one booking.
 *
 * <p>Status changes go through {@link #changeStatus}, which always appends a {@link StatusHistoryEntry}.</p>
 *
 * <p>A RESOLVED_FINAL dispute keeps {@code activeBookingKey} until the booking resolution is confirmed APPLIED
 * (DisputeResolutionStore.recordSettlement), because the booking stays DISPUTED until then.</p>
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
    /** Internal booking-sync state (see BookingSyncState). Null on records created before it existed: SYNCED. */
    private BookingSyncState bookingSyncState;
    /** Internal: until when one worker (a raise request or a reconciler run) holds this PENDING record. Never exposed. */
    private Instant syncLeaseUntil;
    /** Internal: random token of the worker holding the lease; removal is only allowed for that worker. Never exposed. */
    private String syncLeaseOwner;
    /** The admin decision (set when the dispute becomes RESOLVED). Its note is internal. */
    private DisputeDecision decision;
    /** End of the appeal window: decision time + {@link DisputeRules#APPEAL_WINDOW}. */
    private Instant appealDeadline;
    /** The single appeal, if one was filed, and the reviewing admin's verdict. */
    private DisputeAppeal appeal;
    /** The binding decision once RESOLVED_FINAL: the original one, or the reviewer's if the appeal changed it. */
    private DisputeDecision finalDecision;
    private Instant finalizedAt;
    /** Carrying out the final decision in other modules (set when the dispute becomes RESOLVED_FINAL). */
    private DisputeSettlement settlement;
    /** Uploaded evidence files (metadata only; the files are private assets in evidence storage). */
    private List<EvidenceFile> evidenceFiles = new ArrayList<>();
    /**
     * Uploaded evidence files per party role (CUSTOMER / PROVIDER), kept in step with {@link #evidenceFiles} by the
     * same atomic update, so the per-party limit holds under concurrent uploads.
     */
    private Map<String, Integer> evidenceCounts = new HashMap<>();

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

    /** False only for an intent record whose booking change is not confirmed yet (PENDING). */
    public boolean isVisible() {
        return bookingSyncState != BookingSyncState.PENDING;
    }

    /** True if the user is the customer or the provider of the disputed booking. */
    public boolean isParty(String userId) {
        return userId != null && (userId.equals(customerId) || userId.equals(providerId));
    }

    public String getId() {
        return id;
    }

    public BookingSyncState getBookingSyncState() {
        return bookingSyncState;
    }

    public void setBookingSyncState(BookingSyncState bookingSyncState) {
        this.bookingSyncState = bookingSyncState;
    }

    public Instant getSyncLeaseUntil() {
        return syncLeaseUntil;
    }

    public void setSyncLeaseUntil(Instant syncLeaseUntil) {
        this.syncLeaseUntil = syncLeaseUntil;
    }

    public String getSyncLeaseOwner() {
        return syncLeaseOwner;
    }

    public void setSyncLeaseOwner(String syncLeaseOwner) {
        this.syncLeaseOwner = syncLeaseOwner;
    }

    public DisputeDecision getDecision() {
        return decision;
    }

    public void setDecision(DisputeDecision decision) {
        this.decision = decision;
    }

    public Instant getAppealDeadline() {
        return appealDeadline;
    }

    public void setAppealDeadline(Instant appealDeadline) {
        this.appealDeadline = appealDeadline;
    }

    public DisputeAppeal getAppeal() {
        return appeal;
    }

    public void setAppeal(DisputeAppeal appeal) {
        this.appeal = appeal;
    }

    public DisputeDecision getFinalDecision() {
        return finalDecision;
    }

    public void setFinalDecision(DisputeDecision finalDecision) {
        this.finalDecision = finalDecision;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    public DisputeSettlement getSettlement() {
        return settlement;
    }

    public void setSettlement(DisputeSettlement settlement) {
        this.settlement = settlement;
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

    /** Read-only view; files are added only through the evidence store's atomic update. */
    public List<EvidenceFile> getEvidenceFiles() {
        return evidenceFiles == null ? List.of() : Collections.unmodifiableList(evidenceFiles);
    }

    public void setEvidenceFiles(List<EvidenceFile> evidenceFiles) {
        this.evidenceFiles = evidenceFiles == null ? new ArrayList<>() : new ArrayList<>(evidenceFiles);
    }

    public Map<String, Integer> getEvidenceCounts() {
        return evidenceCounts == null ? Map.of() : Collections.unmodifiableMap(evidenceCounts);
    }

    public void setEvidenceCounts(Map<String, Integer> evidenceCounts) {
        this.evidenceCounts = evidenceCounts == null ? new HashMap<>() : new HashMap<>(evidenceCounts);
    }
}
