package com.gamagerecruiters.marketplace.modules.completion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Completion details of a booking (WP08).
 * The booking status itself is owned by WP05; this document stores the
 * completion evidence and timestamps for one booking.
 */
@Document(collection = "service_completions")
public class ServiceCompletion {

    @Id
    private String id;
    private String bookingId;
    private String customerId;
    private String providerId;
    private Instant startedAt;
    private String completionNote;
    private List<String> photoUrls = new ArrayList<>();
    private Instant markedCompletedAt;
    private Instant confirmedAt;
    private CompletionConfirmationType confirmationType;
    private Instant issueRaisedAt;
    private String disputeId;
    private Instant createdAt;
    private Instant updatedAt;

    public ServiceCompletion() {
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

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public String getCompletionNote() {
        return completionNote;
    }

    public void setCompletionNote(String completionNote) {
        this.completionNote = completionNote;
    }

    public List<String> getPhotoUrls() {
        return photoUrls;
    }

    public void setPhotoUrls(List<String> photoUrls) {
        this.photoUrls = photoUrls;
    }

    public Instant getMarkedCompletedAt() {
        return markedCompletedAt;
    }

    public void setMarkedCompletedAt(Instant markedCompletedAt) {
        this.markedCompletedAt = markedCompletedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public CompletionConfirmationType getConfirmationType() {
        return confirmationType;
    }

    public void setConfirmationType(CompletionConfirmationType confirmationType) {
        this.confirmationType = confirmationType;
    }

    public Instant getIssueRaisedAt() {
        return issueRaisedAt;
    }

    public void setIssueRaisedAt(Instant issueRaisedAt) {
        this.issueRaisedAt = issueRaisedAt;
    }

    public String getDisputeId() {
        return disputeId;
    }

    public void setDisputeId(String disputeId) {
        this.disputeId = disputeId;
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
