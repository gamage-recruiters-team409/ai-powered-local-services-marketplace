package com.gamagerecruiters.marketplace.modules.customer.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted record of a customer's favourite provider or service listing (FR-CUST-005).
 * Compound unique index on (customerId, type, targetId) is enforced at the database level.
 */
@Document(collection = "customer_favourites")
public class CustomerFavourite {

    @Id
    private String id;

    private String customerId;

    private FavouriteType type;

    private String targetId;

    private Instant createdAt;

    public CustomerFavourite() {
    }

    public CustomerFavourite(String customerId, FavouriteType type, String targetId, Instant createdAt) {
        this.customerId = customerId;
        this.type = type;
        this.targetId = targetId;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public FavouriteType getType() {
        return type;
    }

    public void setType(FavouriteType type) {
        this.type = type;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
