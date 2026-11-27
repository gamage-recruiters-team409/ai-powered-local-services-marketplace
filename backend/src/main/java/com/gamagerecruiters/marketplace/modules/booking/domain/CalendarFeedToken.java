package com.gamagerecruiters.marketplace.modules.booking.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A user's private calendar subscription link. Only the SHA-256 hash of the secret is stored, so a database leak
 * does not reveal working links. One per user; resetting replaces it and the old link stops working.
 */
@Document(collection = "calendar_feed_tokens")
public class CalendarFeedToken {

    @Id
    private String id;
    private String userId;
    /** CUSTOMER or PROVIDER: which side's bookings the feed shows. */
    private String role;
    private String tokenHash;
    private Instant createdAt;
    private Instant lastUsedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
