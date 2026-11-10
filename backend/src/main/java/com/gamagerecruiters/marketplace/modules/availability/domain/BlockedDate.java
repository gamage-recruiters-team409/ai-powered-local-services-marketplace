package com.gamagerecruiters.marketplace.modules.availability.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A full day the provider is not available. Date is stored as "yyyy-MM-dd".
 */
@Document(collection = "blocked_dates")
public class BlockedDate {

    @Id
    private String id;
    private String providerId;
    private String date;
    private BlockReason reason;
    private String note;
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public BlockReason getReason() {
        return reason;
    }

    public void setReason(BlockReason reason) {
        this.reason = reason;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
